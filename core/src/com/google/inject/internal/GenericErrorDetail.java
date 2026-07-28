package com.google.inject.internal;

import com.google.inject.spi.ErrorDetail;

import java.io.Serializable;
import java.util.Formatter;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Generic error message representing a Guice internal error.
 */
public final class GenericErrorDetail
        extends InternalErrorDetail<GenericErrorDetail>
        implements Serializable
{
    public GenericErrorDetail(
            ErrorId errorId,
            String message,
            List<Object> sources,
            Throwable cause)
    {
        super(errorId, requireNonNull(message, "message"), sources, cause);
    }

    @Override
    public void formatDetail(List<ErrorDetail<?>> mergeableErrors, Formatter formatter)
    {
        checkArgument(mergeableErrors.isEmpty(), "Unexpected mergeable errors");
        List<Object> dependencies = getSources();
        for (Object source : dependencies.reversed()) {
            formatter.format("  ");
            new SourceFormatter(source, formatter, /* omitPreposition= */ false).format();
        }
    }

    @Override
    public GenericErrorDetail withSources(List<Object> newSources)
    {
        return new GenericErrorDetail(errorId, getMessage(), newSources, getCause());
    }
}
