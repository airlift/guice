/*
 * AOP Alliance API. The AOP Alliance published this API into the public domain
 * (http://aopalliance.sourceforge.net). Vendored here unchanged in shape so that classes compiled
 * against stock Guice signatures keep linking without an external aopalliance artifact.
 */
package org.aopalliance.intercept;

import org.aopalliance.aop.Advice;

/**
 * Tag interface for interceptors.
 */
public interface Interceptor
        extends Advice {}
