/*
 * AOP Alliance API. The AOP Alliance published this API into the public domain
 * (http://aopalliance.sourceforge.net). Vendored here unchanged in shape so that classes compiled
 * against stock Guice signatures keep linking without an external aopalliance artifact.
 */
package org.aopalliance.aop;

/**
 * Superclass for all AOP infrastructure exceptions.
 */
public class AspectException
        extends RuntimeException
{
    public AspectException(String message)
    {
        super(message);
    }

    public AspectException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
