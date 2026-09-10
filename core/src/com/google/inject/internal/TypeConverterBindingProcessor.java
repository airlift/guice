/*
 * Copyright (C) 2008 Google Inc.
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

package com.google.inject.internal;

import com.google.common.collect.ImmutableList;
import com.google.inject.TypeLiteral;
import com.google.inject.internal.util.SourceProvider;
import com.google.inject.matcher.AbstractMatcher;
import com.google.inject.matcher.Matcher;
import com.google.inject.matcher.Matchers;
import com.google.inject.spi.Element;
import com.google.inject.spi.TypeConverter;
import com.google.inject.spi.TypeConverterBinding;

import java.lang.reflect.Type;
import java.util.function.Function;

/**
 * Handles {@code Binder.convertToTypes} commands.
 *
 * @author crazybob@google.com (Bob Lee)
 * @author jessewilson@google.com (Jesse Wilson)
 */
final class TypeConverterBindingProcessor
        extends AbstractProcessor
{
    TypeConverterBindingProcessor(Errors errors)
    {
        super(errors);
    }

    /**
     * Installs default converters for primitives, enums, and class literals.
     */
    static void prepareBuiltInConverters(InjectorImpl injector)
    {
        for (TypeConverterBinding builtIn : BUILT_IN_CONVERTERS) {
            injector.getBindingData().addConverter(builtIn);
        }
    }

    /**
     * The built-in converter bindings are identical for every injector: the converters and matchers
     * are stateless, TypeConverterBinding is immutable, and the source is the shared unknown-source
     * constant. Building them per injector cost seven reflective parse-method lookups and a dozen
     * allocations per creation.
     */
    private static final ImmutableList<TypeConverterBinding> BUILT_IN_CONVERTERS = buildBuiltInConverters();

    private static ImmutableList<TypeConverterBinding> buildBuiltInConverters()
    {
        ImmutableList.Builder<TypeConverterBinding> builtIn = ImmutableList.builder();
        // Configure type converters. Parsers are passed as direct method references (rather than
        // looked up reflectively by computed name) so GraalVM native-image's static analysis can
        // resolve them without extra reflection configuration.
        convertToPrimitiveType(builtIn, Integer.class, Integer::parseInt);
        convertToPrimitiveType(builtIn, Long.class, Long::parseLong);
        convertToPrimitiveType(builtIn, Boolean.class, Boolean::parseBoolean);
        convertToPrimitiveType(builtIn, Byte.class, Byte::parseByte);
        convertToPrimitiveType(builtIn, Short.class, Short::parseShort);
        convertToPrimitiveType(builtIn, Float.class, Float::parseFloat);
        convertToPrimitiveType(builtIn, Double.class, Double::parseDouble);

        convertToClass(
                builtIn,
                Character.class,
                new TypeConverter()
                {
                    @Override
                    public Object convert(String value, TypeLiteral<?> toType)
                    {
                        value = value.trim();
                        if (value.length() != 1) {
                            throw new RuntimeException("Length != 1.");
                        }
                        return value.charAt(0);
                    }

                    @Override
                    public String toString()
                    {
                        return "TypeConverter<Character>";
                    }
                });

        convertToClasses(
                builtIn,
                Matchers.subclassesOf(Enum.class),
                new TypeConverter()
                {
                    @SuppressWarnings({"rawtypes", "unchecked"}) // Unavoidable, only way to use Enum.valueOf
                    @Override
                    public Object convert(String value, TypeLiteral<?> toType)
                    {
                        return Enum.valueOf((Class) toType.getRawType(), value);
                    }

                    @Override
                    public String toString()
                    {
                        return "TypeConverter<E extends Enum<E>>";
                    }
                });

        internalConvertToTypes(
                builtIn,
                new AbstractMatcher<TypeLiteral<?>>()
                {
                    @Override
                    public boolean matches(TypeLiteral<?> typeLiteral)
                    {
                        return typeLiteral.getRawType() == Class.class;
                    }

                    @Override
                    public String toString()
                    {
                        return "Class<?>";
                    }
                },
                new TypeConverter()
                {
                    @Override
                    public Object convert(String value, TypeLiteral<?> toType)
                    {
                        try {
                            return Class.forName(value);
                        }
                        catch (ClassNotFoundException e) {
                            throw new RuntimeException(e.getMessage());
                        }
                    }

                    @Override
                    public String toString()
                    {
                        return "TypeConverter<Class<?>>";
                    }
                });
        return builtIn.build();
    }

    private static <T> void convertToPrimitiveType(
            ImmutableList.Builder<TypeConverterBinding> builtIn,
            final Class<T> wrapperType,
            final Function<String, T> parser)
    {
        TypeConverter typeConverter =
                new TypeConverter()
                {
                    @Override
                    public Object convert(String value, TypeLiteral<?> toType)
                    {
                        try {
                            return parser.apply(value);
                        }
                        catch (RuntimeException e) {
                            throw new RuntimeException(e.getMessage());
                        }
                    }

                    @Override
                    public String toString()
                    {
                        return "TypeConverter<" + wrapperType.getSimpleName() + ">";
                    }
                };

        convertToClass(builtIn, wrapperType, typeConverter);
    }

    private static <T> void convertToClass(
            ImmutableList.Builder<TypeConverterBinding> builtIn,
            Class<T> type,
            TypeConverter converter)
    {
        convertToClasses(builtIn, Matchers.identicalTo(type), converter);
    }

    private static void convertToClasses(
            ImmutableList.Builder<TypeConverterBinding> builtIn,
            final Matcher<? super Class<?>> typeMatcher,
            TypeConverter converter)
    {
        internalConvertToTypes(
                builtIn,
                new AbstractMatcher<TypeLiteral<?>>()
                {
                    @Override
                    public boolean matches(TypeLiteral<?> typeLiteral)
                    {
                        Type type = typeLiteral.getType();
                        if (!(type instanceof Class)) {
                            return false;
                        }
                        Class<?> clazz = (Class<?>) type;
                        return typeMatcher.matches(clazz);
                    }

                    @Override
                    public String toString()
                    {
                        return typeMatcher.toString();
                    }
                },
                converter);
    }

    private static void internalConvertToTypes(
            ImmutableList.Builder<TypeConverterBinding> builtIn,
            Matcher<? super TypeLiteral<?>> typeMatcher,
            TypeConverter converter)
    {
        builtIn.add(new TypeConverterBinding(SourceProvider.UNKNOWN_SOURCE, typeMatcher, converter));
    }

    @Override
    protected boolean handles(Element element)
    {
        return element instanceof TypeConverterBinding;
    }

    @Override
    public Boolean visit(TypeConverterBinding command)
    {
        injector
                .getBindingData()
                .addConverter(
                        new TypeConverterBinding(
                                command.getSource(), command.getTypeMatcher(), command.getTypeConverter()));
        return true;
    }
}
