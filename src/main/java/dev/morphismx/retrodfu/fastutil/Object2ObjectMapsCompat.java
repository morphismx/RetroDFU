package dev.morphismx.retrodfu.fastutil;

import dev.morphismx.retrodfu.annotation.Redirect;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectIterable;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectSet;

import java.util.function.Consumer;

/**
 * {@code @Redirect} handlers for {@code Object2ObjectMaps} calls that postdate
 * fastutil 7.1.0.
 */
public final class Object2ObjectMapsCompat {

    private Object2ObjectMapsCompat() {
    }

    /**
     * Mirrors fastutil 8's {@code fastIterable}. When the entry set is a
     * {@code FastEntrySet}, both {@code iterator()} and {@code forEach} use
     * {@code fastIterator()}, which reuses one entry instance instead of
     * allocating one per step. fastutil 7.1.0 has no {@code fastForEach}, so
     * {@code forEach} loops over the fast iterator directly.
     */
    @Redirect(Object2ObjectMaps.class)
    public static <K, V> ObjectIterable<Object2ObjectMap.Entry<K, V>> fastIterable(Object2ObjectMap<K, V> map) {
        ObjectSet<Object2ObjectMap.Entry<K, V>> entries = map.object2ObjectEntrySet();
        if (entries instanceof Object2ObjectMap.FastEntrySet<K, V> fast) {
            return new ObjectIterable<>() {
                @Override
                public ObjectIterator<Object2ObjectMap.Entry<K, V>> iterator() {
                    return fast.fastIterator();
                }

                @Override
                public void forEach(Consumer<? super Object2ObjectMap.Entry<K, V>> consumer) {
                    ObjectIterator<Object2ObjectMap.Entry<K, V>> it = fast.fastIterator();
                    while (it.hasNext()) {
                        consumer.accept(it.next());
                    }
                }
            };
        }
        return entries;
    }
}
