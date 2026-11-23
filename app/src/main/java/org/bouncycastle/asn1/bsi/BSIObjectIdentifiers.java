package org.bouncycastle.asn1.bsi;

/**
 * Shim v0.1.5 — re-expone en la ruta antigua la interfaz que bcprov
 * 1.78.1 movió a org.bouncycastle.internal.asn1.bsi.
 *
 * bcpkix-jdk15to18-1.78.1 (JcaContentSignerBuilder y cía.) sigue
 * referenciando org.bouncycastle.asn1.bsi.BSIObjectIdentifiers, pero
 * bcprov 1.78.1 ya no la contiene en esa ruta: solo existe la interna.
 * Sin esta clase, ART falla al verificar
 * org.bouncycastle.operator.DefaultSignatureNameFinder (su <clinit>
 * toca estos OID) y OperatorHelper queda marcado como erróneo:
 * NoClassDefFoundError: org.bouncycastle.operator.jcajce.OperatorHelper
 * (v0.1.4, error real en el móvil del usuario).
 *
 * La interfaz interna tiene TODOS los campos que bcpkix 1.78.1
 * referencia desde aquí (ecdsa_plain_*), así que basta con heredar:
 * ningún OID literal que adivinar. Código propio (3 interfaces
 * triviales), no código copiado de BouncyCastle (licencia MIT).
 */
public interface BSIObjectIdentifiers
    extends org.bouncycastle.internal.asn1.bsi.BSIObjectIdentifiers {
}
