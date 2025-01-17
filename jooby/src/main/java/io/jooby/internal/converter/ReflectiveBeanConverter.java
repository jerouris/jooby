/**
 * Jooby https://jooby.io
 * Apache License Version 2.0 https://jooby.io/LICENSE.txt
 * Copyright 2014 Edgar Espina
 */
package io.jooby.internal.converter;

import io.jooby.BadRequestException;
import io.jooby.BeanConverter;
import io.jooby.FileUpload;
import io.jooby.MissingValueException;
import io.jooby.Multipart;
import io.jooby.ProvisioningException;
import io.jooby.ValueNode;
import io.jooby.internal.reflect.$Types;

import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static io.jooby.SneakyThrows.propagate;

public class ReflectiveBeanConverter implements BeanConverter {
  private static final String AMBIGUOUS_CONSTRUCTOR =
      "Ambiguous constructor found. Expecting a single constructor or only one annotated with "
          + Inject.class.getName();

  private static final Object[] NO_ARGS = new Object[0];

  @Override public boolean supports(@Nonnull Class type) {
    return true;
  }

  @Override public Object convert(@Nonnull ValueNode node, @Nonnull Class type) {
    try {
      return newInstance(type, node);
    } catch (InstantiationException | IllegalAccessException | NoSuchMethodException x) {
      throw propagate(x);
    } catch (InvocationTargetException x) {
      throw propagate(x.getCause());
    }
  }

  private static <T> T newInstance(Class<T> type, ValueNode node)
      throws IllegalAccessException, InstantiationException, InvocationTargetException,
      NoSuchMethodException {
    Constructor[] constructors = type.getConstructors();
    if (constructors.length == 0) {
      return setters(type.getDeclaredConstructor().newInstance(), node,
          Collections.emptySet());
    }
    Constructor constructor = selectConstructor(constructors);
    Set<ValueNode> state = new HashSet<>();
    Object[] args = constructor.getParameterCount() == 0
        ? NO_ARGS
        : inject(node, constructor, state::add);
    return (T) setters(constructor.newInstance(args), node, state);
  }

  private static Constructor selectConstructor(Constructor[] constructors) {
    Constructor result = null;
    if (constructors.length == 1) {
      result = constructors[0];
    } else {
      for (Constructor constructor : constructors) {
        if (Modifier.isPublic(constructor.getModifiers())) {
          Annotation inject = constructor.getAnnotation(Inject.class);
          if (inject != null) {
            if (result == null) {
              result = constructor;
            } else {
              throw new IllegalStateException(AMBIGUOUS_CONSTRUCTOR);
            }
          }
        }
      }
    }
    if (result == null) {
      throw new IllegalStateException(AMBIGUOUS_CONSTRUCTOR);
    }
    return result;
  }

  public static Object[] inject(ValueNode scope, Executable method, Consumer<ValueNode> state) {
    Parameter[] parameters = method.getParameters();
    if (parameters.length == 0) {
      return NO_ARGS;
    }
    Object[] args = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      Parameter parameter = parameters[i];
      String name = paramName(parameter);
      ValueNode param = scope.get(name);
      state.accept(param);
      args[i] = value(parameter, scope, param);
    }
    return args;
  }

  private static String paramName(Parameter parameter) {
    String name = parameter.getName();
    Named named = parameter.getAnnotation(Named.class);
    if (named != null && named.value().length() > 0) {
      name = named.value();
    }
    return name;
  }

  private static <T> T setters(T newInstance, ValueNode node, Set<ValueNode> skip) {
    Method[] methods = newInstance.getClass().getMethods();
    for (ValueNode value : node) {
      if (!skip.contains(value)) {
        String name = value.name();
        String setter1 = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Method method = findMethod(methods, setter1);
        if (method == null) {
          method = findMethod(methods, name);
        }
        if (method != null) {
          Parameter parameter = method.getParameters()[0];
          try {
            Object arg = value(parameter, node, value);
            method.invoke(newInstance, arg);
          } catch (ProvisioningException x) {
            throw x;
          } catch (InvocationTargetException x) {
            throw new ProvisioningException(parameter, x.getCause());
          } catch (Exception x) {
            throw new ProvisioningException(parameter, x);
          }
        }
      }
    }
    return newInstance;
  }

  private static Object value(Parameter parameter, ValueNode node, ValueNode value) {
    try {
      if (isFileUpload(node, parameter)) {
        Multipart multipart = (Multipart) node;
        if (List.class.isAssignableFrom(parameter.getType())) {
          return multipart.files(value.name());
        } else if (Set.class.isAssignableFrom(parameter.getType())) {
          return new HashSet<>(multipart.files(value.name()));
        } else if (Optional.class.isAssignableFrom(parameter.getType())) {
          List<FileUpload> files = multipart.files(value.name());
          return files.isEmpty() ? Optional.empty() : Optional.of(files.get(0));
        } else {
          return multipart.file(value.name());
        }
      } else {
        if (List.class.isAssignableFrom(parameter.getType())) {
          return value.toList($Types.parameterizedType0(parameter.getParameterizedType()));
        } else if (Set.class.isAssignableFrom(parameter.getType())) {
          return value.toSet($Types.parameterizedType0(parameter.getParameterizedType()));
        } else if (Optional.class.isAssignableFrom(parameter.getType())) {
          return value.toOptional($Types.parameterizedType0(parameter.getParameterizedType()));
        } else {
          if (value.isMissing() && parameter.getType().isPrimitive()) {
            // fail
            value.value();
          }
          return value.to(parameter.getType());
        }
      }
    } catch (MissingValueException x) {
      throw new ProvisioningException(parameter, x);
    } catch (BadRequestException x) {
      throw new ProvisioningException(parameter, x);
    }
  }

  private static boolean isFileUpload(ValueNode node, Parameter parameter) {
    return (node instanceof Multipart) && isFileUpload(parameter.getType()) || isFileUpload(
        $Types.parameterizedType0(parameter.getParameterizedType()));
  }

  private static boolean isFileUpload(Class type) {
    return FileUpload.class == type;
  }

  private static Method findMethod(Method[] methods, String name) {
    for (Method method : methods) {
      if (method.getName().equals(name) && method.getParameterCount() == 1) {
        return method;
      }
    }
    return null;
  }
}
