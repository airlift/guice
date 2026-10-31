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

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotates an injected {@code Optional<T>} parameter or field to receive {@code Optional.empty()}
 * when {@code T} is not bound, rather than fail with "No implementation for T was bound", or an
 * injected {@code Set}, {@code Collection} or {@code Map} to receive an empty one when it is not
 * bound:
 *
 * <pre>{@code
 * class ReportGenerator
 * {
 *     @Inject
 *     ReportGenerator(@EmptyIfUnbound Optional<AuditLog> auditLog) { ... }
 * }
 * }</pre>
 *
 * <p>It may be used on the parameters of {@literal @}{@link Inject} constructors and methods and of
 * {@literal @}{@link Provides} methods, and on {@literal @}{@link Inject} fields. It is not a binding
 * annotation: any binding annotation on the parameter or field qualifies {@code T}, so
 * {@code @EmptyIfUnbound @Named("audit") Optional<AuditLog>} is present when
 * {@code @Named("audit") AuditLog} is bound.
 *
 * <p>{@code T} is resolved as it would be if it were injected directly, including with a
 * just-in-time binding, so an {@code Optional} of a class with an injectable constructor is always
 * present. It is empty only when {@code T} has no binding and none can be created: for example,
 * when {@code T} is an interface or an annotated key that no module binds, a class without an
 * injectable constructor, or any type not explicitly bound when the injector requires explicit
 * bindings. Any other error resolving {@code T}, such as a missing dependency of its constructor, is
 * reported as usual. A binding that provides {@code null} for {@code T} gives an empty
 * {@code Optional}.
 *
 * <p>If {@code Optional<T>} itself is bound, for example by
 * {@link com.google.inject.multibindings.OptionalBinder}, that binding is used and this annotation
 * has no effect.
 *
 * <p>An {@code OptionalInt}, {@code OptionalLong} or {@code OptionalDouble} may be annotated in the
 * same way, to wrap the value bound to {@code int}, {@code long} or {@code double}, which share the
 * keys of {@code Integer}, {@code Long} and {@code Double}. A constant bound with
 * {@code bindConstant()} is converted as it would be for an {@code int} parameter:
 *
 * <pre>{@code
 * @Inject
 * Server(@EmptyIfUnbound @Named("port") OptionalInt port) { ... }
 * }</pre>
 *
 * <p>An injected {@code Set}, {@code Collection} or {@code Map}, such as one bound by
 * {@link com.google.inject.multibindings.Multibinder} or
 * {@link com.google.inject.multibindings.MapBinder}, may also be annotated, to receive an empty,
 * immutable collection when it is not bound. This lets a class depend on contributions that no
 * module may make:
 *
 * <pre>{@code
 * @Inject
 * ReportGenerator(@EmptyIfUnbound Set<ReportSection> sections) { ... }
 * }</pre>
 *
 * <p>The collection is empty only when nothing binds it, so one that a {@code Multibinder} or
 * {@code MapBinder} declares is used as usual, even when no module adds to it. As with an
 * {@code Optional}, any binding annotation qualifies the collection, as it does the binder's.
 *
 * @see com.google.inject.multibindings.OptionalBinder
 * @see com.google.inject.multibindings.Multibinder
 * @see com.google.inject.multibindings.MapBinder
 */
@Target({PARAMETER, FIELD})
@Retention(RUNTIME)
@Documented
public @interface EmptyIfUnbound {}
