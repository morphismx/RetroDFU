package dev.morphismx.retrodfu.transformer;

import com.google.common.collect.ImmutableMap;
import dev.morphismx.retrodfu.fastutil.FastutilCompat;
import dev.morphismx.retrodfu.guava.GuavaCompat;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;

import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * Redirects {@code JavaOps}' calls to fastutil 8 / newer-guava members onto the
 * compat implementations. Handlers are instance methods because the targeted
 * methods are; {@code mapLikeToMap} is static, so its handler is static too.
 */
@CTransformer(name = "com.mojang.serialization.JavaOps")
abstract class JavaOpsTransformer {

    @CRedirect(method = "createIntList", target = @CTarget(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/ints/IntArrayList;toList(Ljava/util/stream/IntStream;)Lit/unimi/dsi/fastutil/ints/IntArrayList;"))
    IntArrayList toIntList(IntStream stream) {
        return FastutilCompat.toList(stream);
    }

    @CRedirect(method = "createLongList", target = @CTarget(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongArrayList;toList(Ljava/util/stream/LongStream;)Lit/unimi/dsi/fastutil/longs/LongArrayList;"))
    LongArrayList toLongList(LongStream stream) {
        return FastutilCompat.toList(stream);
    }

    @CRedirect(method = {"convertTo", "getIntStream"}, target = @CTarget(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/ints/IntList;intStream()Ljava/util/stream/IntStream;"))
    IntStream intStream(IntList list) {
        return FastutilCompat.intStream(list);
    }

    @CRedirect(method = {"convertTo", "getLongStream"}, target = @CTarget(value = "INVOKE",
            target = "Lit/unimi/dsi/fastutil/longs/LongList;longStream()Ljava/util/stream/LongStream;"))
    LongStream longStream(LongList list) {
        return FastutilCompat.longStream(list);
    }

    @CRedirect(method = "mergeToMap", target = @CTarget(value = "INVOKE",
            target = "Lcom/google/common/collect/ImmutableMap;builderWithExpectedSize(I)Lcom/google/common/collect/ImmutableMap$Builder;"))
    ImmutableMap.Builder<Object, Object> builderWithExpectedSize(int expectedSize) {
        return GuavaCompat.mapBuilderWithExpectedSize(expectedSize);
    }

    @CRedirect(method = "mergeToMap", target = @CTarget(value = "INVOKE",
            target = Targets.BUILD_KEEPING_LAST))
    ImmutableMap<Object, Object> buildKeepingLast(ImmutableMap.Builder<Object, Object> builder) {
        return GuavaCompat.buildKeepingLast(builder);
    }

    @CRedirect(method = "createMap(Ljava/util/stream/Stream;)Ljava/lang/Object;", target = @CTarget(value = "INVOKE",
            target = Targets.TO_IMMUTABLE_MAP))
    Collector<Object, ?, ImmutableMap<Object, Object>> toImmutableMap(
            Function<Object, Object> keyFunction, Function<Object, Object> valueFunction) {
        return GuavaCompat.toImmutableMap(keyFunction, valueFunction);
    }

    // mapLikeToMap is a static method, so the handler must be static as well;
    // private is safe here because INVOKESTATIC never targets it virtually.
    @CRedirect(method = "mapLikeToMap", target = @CTarget(value = "INVOKE",
            target = Targets.TO_IMMUTABLE_MAP))
    private static Collector<Object, ?, ImmutableMap<Object, Object>> toImmutableMapStatic(
            Function<Object, Object> keyFunction, Function<Object, Object> valueFunction) {
        return GuavaCompat.toImmutableMap(keyFunction, valueFunction);
    }
}
