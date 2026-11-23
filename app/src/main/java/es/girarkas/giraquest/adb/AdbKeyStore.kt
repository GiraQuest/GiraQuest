package es.girarkas.giraquest.adb

import android.content.Context
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date

/**
 * Claves RSA persistentes para el ADB embebido (equivale a las adbkey del
 * PC). libadb-android las necesita para el pairing SPAKE2/TLS y para que
 * el emparejamiento sea "una sola vez": si cambian las claves, el sistema
 * pide emparejar de nuevo.
 *
 * Se guardan en el almacenamiento privado de la app (nadie más las lee).
 */
class AdbKeyStore(context: Context) {

    private val keyFile = File(context.filesDir, "adb_key.pk8")
    private val certFile = File(context.filesDir, "adb_cert.der")

    data class Keys(val privateKey: PrivateKey, val certificate: Certificate)

    /** ¿Existen ya las claves guardadas en disco? (v0.1.22: para el log, distinguir "cargadas" de "creadas nuevas".) */
    fun keysExist(): Boolean = keyFile.exists() && certFile.exists()

    /**
     * Huella SHA-256 de la clave pública, en el formato que muestra el
     * diálogo de autorización de Android ("SHA256:<base64 sin padding>").
     * Sirve para demostrar en el log que la clave es LA MISMA en cada
     * arranque: si la huella cambia, el móvil trata a la app como un
     * cliente nuevo y dispara el diálogo de autorización.
     */
    fun fingerprint(certificate: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(certificate.publicKey.encoded)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun loadOrCreate(): Keys {
        if (keyFile.exists() && certFile.exists()) {
            try {
                val kf = KeyFactory.getInstance("RSA")
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = CertificateFactory.getInstance("X.509")
                    .generateCertificate(FileInputStream(certFile))
                return Keys(priv, cert)
            } catch (_: Exception) {
                keyFile.delete()
                certFile.delete()
            }
        }
        return create()
    }

    private fun create(): Keys {
        // v0.1.1: NO usar el proveedor por nombre "BC". El "BC" del sistema
        // es una copia recortada de BouncyCastle que varía según el Android
        // (en algunos no tiene SHA512withRSA o intercepta la llamada antes
        // que el jar), y es un sospechoso del crash al abrir en el móvil.
        // Se usa SIEMPRE el BouncyCastle del jar, pasado como objeto
        // Provider, sin tocar Security.addProvider ni depender del "BC"
        // del sistema.
        val bc = BouncyCastleProvider()
        val kpg = KeyPairGenerator.getInstance("RSA", bc)
        kpg.initialize(2048, SecureRandom())
        val pair = kpg.generateKeyPair()
        val cert = selfSigned(pair.getPublic(), pair.getPrivate(), bc)
        FileOutputStream(keyFile).use { it.write(pair.getPrivate().encoded) }
        FileOutputStream(certFile).use { it.write(cert.encoded) }
        return Keys(pair.getPrivate(), cert)
    }

    private fun selfSigned(
        publicKey: PublicKey,
        privateKey: PrivateKey,
        bc: BouncyCastleProvider,
    ): Certificate {
        val now = Date()
        // Validez 10 años: el emparejamiento no debe caducar.
        val until = Date(now.time + 10L * 365 * 24 * 60 * 60 * 1000)
        val subject = X500Name("CN=GiraQuest")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            now,
            until,
            subject,
            publicKey
        )
        val signer = JcaContentSignerBuilder("SHA512withRSA")
            .setProvider(bc)
            .build(privateKey)
        return JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))
    }
}
