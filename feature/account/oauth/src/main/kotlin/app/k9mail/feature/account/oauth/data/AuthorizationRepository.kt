package app.k9mail.feature.account.oauth.data

import android.content.Intent
import androidx.core.net.toUri
import app.k9mail.core.common.oauth.OAuthConfiguration
import app.k9mail.feature.account.oauth.domain.DomainContract
import app.k9mail.feature.account.oauth.domain.entity.AuthorizationIntentResult
import app.k9mail.feature.account.oauth.domain.entity.AuthorizationResult
import app.k9mail.feature.account.oauth.domain.entity.AuthorizationState
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import timber.log.Timber

class AuthorizationRepository(
    private val service: AuthorizationService,
) : DomainContract.AuthorizationRepository {

    override fun getAuthorizationRequestIntent(
        configuration: OAuthConfiguration,
        emailAddress: String,
    ): AuthorizationIntentResult {
        return AuthorizationIntentResult.Success(
            createAuthorizationRequestIntent(configuration, emailAddress),
        )
    }

    override suspend fun getAuthorizationResponse(intent: Intent): AuthorizationResponse? {
        return try {
            AuthorizationResponse.fromIntent(intent)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Error deserializing AuthorizationResponse")
            null
        }
    }

    override suspend fun getAuthorizationException(intent: Intent): AuthorizationException? {
        return try {
            AuthorizationException.fromIntent(intent)
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "Error deserializing AuthorizationException")
            null
        }
    }

    override suspend fun getExchangeToken(
        authorizationState: AuthorizationState,
        response: AuthorizationResponse,
    ): AuthorizationResult = suspendCoroutine { continuation ->
        val tokenRequest = response.createTokenExchangeRequest()
        val authState = authorizationState.toAuthState()

        service.performTokenRequest(tokenRequest) { tokenResponse, authorizationException ->
            authState.update(tokenResponse, authorizationException)

            val result = if (authorizationException != null) {
                AuthorizationResult.Failure(authorizationException)
            } else if (tokenResponse != null) {
                AuthorizationResult.Success(authState.toAuthorizationState())
            } else {
                AuthorizationResult.Failure(Exception("Unknown error"))
            }

            continuation.resume(result)
        }
    }

    private fun createAuthorizationRequestIntent(configuration: OAuthConfiguration, emailAddress: String): Intent {
        val serviceConfig = AuthorizationServiceConfiguration(
            configuration.authorizationEndpoint.toUri(),
            configuration.tokenEndpoint.toUri(),
        )

        val authRequestBuilder = AuthorizationRequest.Builder(
            serviceConfig,
            configuration.clientId,
            ResponseTypeValues.CODE,
            configuration.redirectUri.toUri(),
        )

        val authRequest = authRequestBuilder
            .setScope(configuration.scopes.joinToString(" "))
            .setCodeVerifier(null)
            .setLoginHint(emailAddress)
            .build()

        return service.getAuthorizationRequestIntent(authRequest)
    }
}
