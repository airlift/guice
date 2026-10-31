package com.google.inject.internal;

import com.google.inject.Key;
import com.google.inject.spi.ErrorDetail;

import java.util.Formatter;
import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Error reported when the injector requires explicit bindings and a key is not explicitly bound.
 */
final class JitDisabledError
        extends InternalErrorDetail<JitDisabledError>
{
    private final Key<?> key;

    JitDisabledError(Key<?> key, List<Object> sources)
    {
        super(ErrorId.JIT_DISABLED,
                Messages.format("Explicit bindings are required and %s is not explicitly bound.", key),
                sources,
                null);
        this.key = key;
    }

    Key<?> getKey()
    {
        return key;
    }

    @Override
    public void formatDetail(List<ErrorDetail<?>> mergeableErrors, Formatter formatter)
    {
        checkArgument(mergeableErrors.isEmpty(), "Unexpected mergeable errors");
        for (Object source : getSources().reversed()) {
            formatter.format("  ");
            new SourceFormatter(source, formatter, /* omitPreposition= */ false).format();
        }
    }

    @Override
    public JitDisabledError withSources(List<Object> newSources)
    {
        return new JitDisabledError(key, newSources);
    }
}
