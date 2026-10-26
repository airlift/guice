/*
 * Copyright (C) 2006 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject;

import com.google.inject.internal.Annotations;

import java.lang.annotation.Annotation;

import static java.util.Objects.requireNonNull;

class BoundForImpl
        implements BoundFor
{
    private final Class<?> value;

    BoundForImpl(Class<?> value)
    {
        this.value = requireNonNull(value, "value");
    }

    @Override
    public Class<?> value()
    {
        return value;
    }

    @Override
    public int hashCode()
    {
        // This is specified in java.lang.Annotation.
        return (127 * "value".hashCode()) ^ value.hashCode();
    }

    @Override
    public boolean equals(Object o)
    {
        if (!(o instanceof BoundFor other)) {
            return false;
        }

        return value.equals(other.value());
    }

    @Override
    public String toString()
    {
        return '@' + BoundFor.class.getName() + '(' + Annotations.memberValueString("value", value) + ')';
    }

    @Override
    public Class<? extends Annotation> annotationType()
    {
        return BoundFor.class;
    }
}
