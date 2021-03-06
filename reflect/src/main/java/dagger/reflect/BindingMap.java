/*
 * Copyright (C) 2018 Google Inc.
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
package dagger.reflect;

import dagger.reflect.Binding.LinkedBinding;
import dagger.reflect.Binding.UnlinkedBinding;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * A specialized map for {@linkplain Key keys} to {@linkplain Binding bindings}. This map
 * intentionally offers a limited API in order to enforce a certain lifecycle of its entries.
 */
final class BindingMap {
  private final ConcurrentHashMap<Key, Binding> bindings;

  private BindingMap(ConcurrentHashMap<Key, Binding> bindings) {
    this.bindings = bindings;
  }

  @Nullable Binding get(Key key) {
    return bindings.get(key);
  }

  /**
   * Try to add an entry for {@code key} mapped to {@code binding}. This operation should only be
   * performed immediately after {@link #get(Key)} returns null.
   *
   * @return The binding now associated with {@code key}. This may not be the same instance as
   * {@code binding} if two threads concurrently perform the {@code get}/{@code putIfAbsent} dance.
   */
  Binding putIfAbsent(Key key, Binding binding) {
    Binding replaced = bindings.putIfAbsent(key, binding);
    return replaced != null
        ? replaced // You raced another thread and lost.
        : binding;
  }

  /**
   * Replace the {@linkplain UnlinkedBinding <code>unlinked<code> binding} mapped from {@code key}
   * with the {@linkplain LinkedBinding <code>linked</code> binding}.
   *
   * @return The linked binding now associated with {@code key}. This may not be the same instance
   * as {@code linked} if two threads concurrently invoke this method.
   */
  LinkedBinding<?> replace(Key key, UnlinkedBinding unlinked, LinkedBinding<?> linked) {
    if (!bindings.replace(key, unlinked, linked)) {
      // If replace() returned false we raced another thread and lost. Return the winner.
      LinkedBinding<?> race = (LinkedBinding<?>) bindings.get(key);
      if (race == null) throw new AssertionError();
      return race;
    }
    return linked;
  }

  static final class Builder {
    private final Map<Key, Binding> bindings = new LinkedHashMap<>();
    private final Map<Key, SetBindings> setBindings = new LinkedHashMap<>();
    private final Map<Key, Map<Object, Binding>> mapBindings = new LinkedHashMap<>();

    Builder add(Key key, Binding binding) {
      Binding replaced = bindings.put(key, binding);
      if (replaced != null) {
        throw new IllegalStateException(
            "Duplicate binding for " + key + ": " + replaced + " and " + binding);
      }
      return this;
    }

    Builder addIntoSet(Key key, Binding elementBinding) {
      SetBindings bindings = setBindings.get(key);
      if (bindings == null) {
        bindings = new SetBindings();
        setBindings.put(key, bindings);
      }
      bindings.elementBindings.add(elementBinding);

      return this;
    }

    Builder addElementsIntoSet(Key key, Binding elementsBinding) {
      SetBindings bindings = setBindings.get(key);
      if (bindings == null) {
        bindings = new SetBindings();
        setBindings.put(key, bindings);
      }
      bindings.elementsBindings.add(elementsBinding);

      return this;
    }

    Builder addIntoMap(Key key, Object entryKey, Binding entryValueBinding) {
      Map<Object, Binding> mapBinding = mapBindings.get(key);
      if (mapBinding == null) {
        mapBinding = new LinkedHashMap<>();
        mapBindings.put(key, mapBinding);
      }
      Binding replaced = mapBinding.put(entryKey, entryValueBinding);
      if (replaced != null) {
        throw new IllegalStateException(); // TODO duplicate keys
      }

      return this;
    }

    BindingMap build() {
      ConcurrentHashMap<Key, Binding> allBindings = new ConcurrentHashMap<>(bindings);

      // Coalesce all of the bindings for each key into a single set binding.
      for (Map.Entry<Key, SetBindings> setBinding : setBindings.entrySet()) {
        Key key = setBinding.getKey();

        // Take a defensive copy in case the builder is being re-used.
        SetBindings setBindings = setBinding.getValue();
        List<Binding> elementBindings = new ArrayList<>(setBindings.elementBindings);
        List<Binding> elementsBindings = new ArrayList<>(setBindings.elementsBindings);

        Binding replaced =
            allBindings.put(key, new UnlinkedSetBinding(elementBindings, elementsBindings));
        if (replaced != null) {
          throw new IllegalStateException(); // TODO implicit set binding duplicates explicit one.
        }
      }

      // Coalesce all of the bindings for each key into a single map binding.
      for (Map.Entry<Key, Map<Object, Binding>> mapBinding : mapBindings.entrySet()) {
        Key key = mapBinding.getKey();

        // Take a defensive copy in case the builder is being re-used.
        Map<Object, Binding> entryBindings = new LinkedHashMap<>(mapBinding.getValue());

        Binding replaced = allBindings.put(key, new UnlinkedMapBinding(entryBindings));
        if (replaced != null) {
          throw new IllegalStateException(); // TODO implicit map binding duplicates explicit one.
        }
      }

      return new BindingMap(allBindings);
    }
  }

  static final class SetBindings {
    /** Bindings which produce a single element for the target set. */
    final List<Binding> elementBindings = new ArrayList<>();
    /** Bindings which produce a set of elements for the target set. */
    final List<Binding> elementsBindings = new ArrayList<>();
  }
}
