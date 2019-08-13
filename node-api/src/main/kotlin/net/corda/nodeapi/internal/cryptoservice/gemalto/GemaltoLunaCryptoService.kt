package net.corda.nodeapi.internal.cryptoservice.gemalto

import com.safenetinc.luna.LunaSlotManager
import com.safenetinc.luna.provider.LunaProvider
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigFactory
import net.corda.core.crypto.Crypto
import net.corda.core.crypto.SignatureScheme
import net.corda.core.utilities.detailedLogger
import net.corda.core.utilities.trace
import net.corda.nodeapi.internal.config.UnknownConfigurationKeysException
import net.corda.nodeapi.internal.config.parseAs
import net.corda.nodeapi.internal.cryptoservice.CryptoService
import net.corda.nodeapi.internal.cryptoservice.JCACryptoService
import net.corda.nodeapi.internal.cryptoservice.WrappedPrivateKey
import net.corda.nodeapi.internal.cryptoservice.WrappingMode
import java.nio.file.Path
import java.security.KeyStore
import java.security.Provider
import java.security.PublicKey
import javax.security.auth.x500.X500Principal

class GemaltoLunaCryptoService(
        keyStore: KeyStore,
        provider: Provider,
        x500Principal: X500Principal = DUMMY_X500_PRINCIPAL,
        private val auth: () -> GemaltoLunaConfiguration
) : JCACryptoService(keyStore, provider, x500Principal) {

    override fun isLoggedIn(): Boolean {
        return try {
            // Will throw LunaCryptokiException on network failure
            LunaSlotManager.getInstance().isLoggedIn
        } catch (e: Exception) {
            detailedLogger.error("Exception when checking logged in status", e)
            false
        }
    }

    override fun logIn() {
        val config = auth()
        // Force reinitialization to allow reconnect after network failure
        LunaSlotManager.getInstance().reinitialize(true)
        keyStore.load(config.keyStore.byteInputStream(), config.password.toCharArray())
    }

    override fun defaultIdentitySignatureScheme(): SignatureScheme {
        return DEFAULT_IDENTITY_SIGNATURE_SCHEME
    }

    override fun defaultTLSSignatureScheme(): SignatureScheme {
        return DEFAULT_TLS_SIGNATURE_SCHEME
    }

    override fun generateKeyPair(alias: String, scheme: SignatureScheme): PublicKey {
        return withAuthentication {
            detailedLogger.trace { "CryptoService(action=generate_key_pair_start;alias=$alias;scheme=$scheme)" }
            val keyPairGenerator = keyPairGeneratorFromScheme(scheme)
            val keyPair = keyPairGenerator.generateKeyPair()
            keyStore.setKeyEntry(alias, keyPair.private, null, selfSign(scheme, keyPair))
            keyStore.store(null, null) // Gemalto-specific
            detailedLogger.trace { "CryptoService(action=generate_key_pair_end;alias=$alias;scheme=$scheme)" }
            // We call toSupportedKey because it's possible that the PublicKey object returned by the provider is not initialized.
            Crypto.toSupportedPublicKey(keyPair.public)
        }
    }

    /*
     * Unfortunately `calling LunaSlotManager.getInstance().isLoggedIn` is not reliable.
     * It is possible that isLoggedIn is true but a subsequent call on the a Luna JCA object like this.keyStore to throw an Exception
     * because the object is not initialized.
     */
    override fun <T> withAuthentication(block: () -> T): T {
        return try {
            super.withAuthentication(block)
        } catch (e: Exception) {
            logIn()
            block()
        }
    }

    override fun createWrappingKey(alias: String, failIfExists: Boolean) {
        throw UnsupportedOperationException()
    }

    override fun generateWrappedKeyPair(masterKeyAlias: String, childKeyScheme: SignatureScheme): Pair<PublicKey, WrappedPrivateKey> {
        throw UnsupportedOperationException()
    }

    override fun sign(masterKeyAlias: String, wrappedPrivateKey: WrappedPrivateKey, payloadToSign: ByteArray): ByteArray {
        throw UnsupportedOperationException()
    }

    override fun getWrappingMode(): WrappingMode? = null

    companion object {
        private val detailedLogger = detailedLogger()

        val KEYSTORE_TYPE = "Luna"
        fun fromConfigurationFile(legalName: X500Principal, cryptoServiceConf: Path?): CryptoService {
            val config = parseConfigFile(cryptoServiceConf!!)
            val provider = LunaProvider.getInstance()
            val keyStore = KeyStore.getInstance(KEYSTORE_TYPE, provider)
            return GemaltoLunaCryptoService(keyStore, provider, legalName) { config }
        }

        fun parseConfigFile(cryptoServiceConf: Path): GemaltoLunaConfiguration {
            try {
                checkConfigurationFileExists(cryptoServiceConf)
                val config = ConfigFactory.parseFile(cryptoServiceConf.toFile()).resolve()
                return config.parseAs(GemaltoLunaConfiguration::class)
            } catch (e: Exception) {
                when(e) {
                    is ConfigException, is UnknownConfigurationKeysException -> throw Exception("Error in ${cryptoServiceConf.toFile().absolutePath} : ${e.message}")
                    else -> throw e
                }
            }
        }

        val DEFAULT_IDENTITY_SIGNATURE_SCHEME = Crypto.ECDSA_SECP256R1_SHA256
        val DEFAULT_TLS_SIGNATURE_SCHEME = Crypto.ECDSA_SECP256R1_SHA256
    }

    data class GemaltoLunaConfiguration(val keyStore: String, val password: String)
}