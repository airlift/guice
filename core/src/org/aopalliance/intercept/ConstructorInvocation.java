/*
 * AOP Alliance API. The AOP Alliance published this API into the public domain
 * (http://aopalliance.sourceforge.net). Vendored here unchanged in shape so that classes compiled
 * against stock Guice signatures keep linking without an external aopalliance artifact.
 */
package org.aopalliance.intercept;

import java.lang.reflect.Constructor;

/**
 * A constructor invocation joinpoint.
 */
public interface ConstructorInvocation
        extends Invocation
{
    Constructor<?> getConstructor();
}
