package net.morphismmc.retrodfu;

import com.google.common.collect.ImmutableMap;
import net.lenni0451.classtransform.annotations.CTarget;
import net.lenni0451.classtransform.annotations.CTransformer;
import net.lenni0451.classtransform.annotations.injection.CRedirect;

/**
 * Redirects the seven-pair {@code ImmutableMap.of} call in
 * {@code CodecTests.recordCodec_maintainFieldOrder} — the game's guava stops
 * at five pairs; a builder keeps order and rejects duplicates, so the
 * replacement is semantics-identical.
 */
@CTransformer(name = "com.mojang.serialization.CodecTests")
abstract class CodecTestsTransformer {

    @CRedirect(
            method = "recordCodec_maintainFieldOrder",
            target =
                    @CTarget(
                            value = "INVOKE",
                            target =
                                    "Lcom/google/common/collect/ImmutableMap;of(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Lcom/google/common/collect/ImmutableMap;"))
    ImmutableMap<Object, Object> of(
            Object k1,
            Object v1,
            Object k2,
            Object v2,
            Object k3,
            Object v3,
            Object k4,
            Object v4,
            Object k5,
            Object v5,
            Object k6,
            Object v6,
            Object k7,
            Object v7) {
        return ImmutableMap.builder()
                .put(k1, v1)
                .put(k2, v2)
                .put(k3, v3)
                .put(k4, v4)
                .put(k5, v5)
                .put(k6, v6)
                .put(k7, v7)
                .build();
    }
}
