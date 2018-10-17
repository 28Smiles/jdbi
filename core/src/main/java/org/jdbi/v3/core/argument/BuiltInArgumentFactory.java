/*
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
package org.jdbi.v3.core.argument;

import java.lang.reflect.Type;
import java.sql.PreparedStatement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.statement.SqlStatement;

import static org.jdbi.v3.core.generic.GenericTypes.getErasedType;

/**
 * The BuiltInArgumentFactory provides instances of {@link Argument} for
 * many core Java types.  Generally you should not need to use this
 * class directly, but instead should bind your object with the
 * {@link SqlStatement} convenience methods.
 *
 * @deprecated will be replaced by a plugin
 */
@Deprecated
public class BuiltInArgumentFactory implements ArgumentFactory {
    // Care for the initialization order here, there's a fair number of statics.  Create the builders before the factory instance.

    private static final ArgumentFactory ENUMS = new EnumArgumentFactory();
    private static final ArgumentFactory OPTIONALS = new OptionalArgumentFactory();
    private static final ArgumentFactory NULL = new UntypedNullArgumentFactory();

    private static final Map<Class<?>, ArgBuilder<?>> BUILDERS = createInternalBuilders();
    private static final List<ArgumentFactory> FACTORIES = Arrays.asList(
        new PrimitivesArgumentFactory(),
        new BoxedArgumentFactory(),
        new EssentialsArgumentFactory(),
        new SqlArgumentFactory(),
        new InternetArgumentFactory()
    );

    public static final ArgumentFactory INSTANCE = new BuiltInArgumentFactory();

    private static <T> void register(Map<Class<?>, ArgBuilder<?>> map, Class<T> klass, int type, StatementBinder<T> binder) {
        register(map, klass, v -> new BuiltInArgument<>(klass, type, binder, v));
    }

    private static <T> void register(Map<Class<?>, ArgBuilder<?>> map, Class<T> klass, ArgBuilder<T> builder) {
        map.put(klass, builder);
    }

    /** Create a binder which calls String.valueOf on its argument and then delegates to another binder. */
    private static <T> StatementBinder<T> stringifyValue(StatementBinder<String> real) {
        return (p, i, v) -> real.bind(p, i, String.valueOf(v));
    }

    private static Map<Class<?>, ArgBuilder<?>> createInternalBuilders() {
        final Map<Class<?>, ArgBuilder<?>> map = new IdentityHashMap<>();

        register(map, java.util.Date.class, Types.TIMESTAMP, (p, i, v) -> p.setTimestamp(i, new Timestamp(v.getTime())));
        register(map, java.sql.Date.class, Types.DATE, PreparedStatement::setDate);
        register(map, Time.class, Types.TIME, PreparedStatement::setTime);
        register(map, Timestamp.class, Types.TIMESTAMP, PreparedStatement::setTimestamp);

        register(map, Instant.class, Types.TIMESTAMP, (p, i, v) -> p.setTimestamp(i, Timestamp.from(v)));
        register(map, LocalDate.class, Types.DATE, (p, i, v) -> p.setDate(i, java.sql.Date.valueOf(v)));
        register(map, LocalTime.class, Types.TIME, (p, i, v) -> p.setTime(i, Time.valueOf(v)));
        register(map, LocalDateTime.class, Types.TIMESTAMP, (p, i, v) -> p.setTimestamp(i, Timestamp.valueOf(v)));
        register(map, OffsetDateTime.class, Types.TIMESTAMP, (p, i, v) -> p.setTimestamp(i, Timestamp.from(v.toInstant())));
        register(map, ZonedDateTime.class, Types.TIMESTAMP, (p, i, v) -> p.setTimestamp(i, Timestamp.from(v.toInstant())));

        return Collections.unmodifiableMap(map);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Argument> build(Type expectedType, Object value, ConfigRegistry config) {
        Class<?> expectedClass = getErasedType(expectedType);

        if (value != null && expectedClass == Object.class) {
            expectedClass = value.getClass();
        }

        Optional<Optional<Argument>> delegated = FACTORIES.stream()
            .map(factory -> factory.build(expectedType, value, config))
            .filter(Optional::isPresent)
            .findFirst();
        if (delegated.isPresent()) {
            return delegated.get();
        }

        @SuppressWarnings("rawtypes")
        ArgBuilder v = BUILDERS.get(expectedClass);

        if (v != null) {
            return Optional.of(v.build(value));
        }

        // Enums must be bound as VARCHAR.
        Optional<Argument> possibleEnum = ENUMS.build(expectedType, value, config);
        if (possibleEnum.isPresent()) {
            return possibleEnum;
        }

        Optional<Argument> maybeOptional = OPTIONALS.build(expectedType, value, config);
        if (maybeOptional.isPresent()) {
            return maybeOptional;
        }

        return NULL.build(expectedType, value, config);
    }
}
