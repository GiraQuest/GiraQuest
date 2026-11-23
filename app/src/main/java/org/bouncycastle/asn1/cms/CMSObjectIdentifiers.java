package org.bouncycastle.asn1.cms;

/**
 * Shim v0.1.5 — re-expone en la ruta antigua la interfaz que bcprov
 * 1.78.1 movió a org.bouncycastle.internal.asn1.cms.
 *
 * bcpkix-jdk15to18-1.78.1 sigue referenciando
 * org.bouncycastle.asn1.cms.CMSObjectIdentifiers, pero bcprov 1.78.1
 * ya no la contiene en esa ruta: solo existe la interna. Sin esta
 * clase, ART falla al verificar
 * org.bouncycastle.operator.DefaultSignatureNameFinder (su <clinit>
 * toca estos OID) y OperatorHelper queda marcado como erróneo:
 * NoClassDefFoundError: org.bouncycastle.operator.jcajce.OperatorHelper
 * (v0.1.4, error real en el móvil del usuario).
 *
 * La interfaz interna tiene TODOS los campos que las clases del flujo
 * de claves (DefaultSignatureNameFinder,
 * DefaultDigestAlgorithmIdentifierFinder,
 * DefaultSignatureAlgorithmIdentifierFinder) referencian desde aquí
 * (id_RSASSA_PSS_SHAKE128/256, id_ecdsa_with_shake128/256). Tres
 * campos que bcpkix referencia (id_alg_cek_hkdf_sha256, id_ori_kem,
 * zlibCompress) NO están en la interna, pero solo los usan clases CMS
 * de datos cifrados/comprimidos (CMSEnvelopedHelper,
 * JceCMSContentEncryptorBuilder, ZlibCompressor...) que nuestro flujo
 * (generar el par RSA + certificado X.509 autofirmado) nunca carga;
 * ART solo verifica las clases que se cargan. Si algún día se usa CMS
 * enveloped, habrá que declarar esos 3 OID aquí. Código propio
 * (interfaz trivial), no código copiado de BouncyCastle (licencia MIT).
 */
public interface CMSObjectIdentifiers
    extends org.bouncycastle.internal.asn1.cms.CMSObjectIdentifiers {
}
