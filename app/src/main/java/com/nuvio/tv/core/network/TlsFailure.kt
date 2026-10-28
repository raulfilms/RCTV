package com.nuvio.tv.core.network

import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * RCTV (RCTV-AUD-021): the failure is the server's certificate not checking out (expired,
 * self-signed or from an unknown authority, or issued for another name), as opposed to the
 * network, a timeout or a refused login. Lets a screen say so instead of a generic error,
 * without naming the server or showing its address.
 */
fun Throwable.isTlsCertificateFailure(): Boolean =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSES)
        .any { it is SSLPeerUnverifiedException || it is CertificateException || it is CertPathValidatorException }

private const val MAX_CAUSES = 8
