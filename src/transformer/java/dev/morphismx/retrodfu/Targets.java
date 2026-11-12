package dev.morphismx.retrodfu;

/**
 * {@code @CTarget} call-site descriptors shared by more than one transformer.
 * They must live on a dedicated class — a field inside a {@code @CTransformer}
 * class would be copied into the target. Being compile-time constants they
 * inline into the annotation, so nothing here is referenced at runtime.
 */
final class Targets {

    static final String BUILD_KEEPING_LAST =
            "Lcom/google/common/collect/ImmutableMap$Builder;buildKeepingLast()Lcom/google/common/collect/ImmutableMap;";
    static final String FAST_ITERABLE =
            "Lit/unimi/dsi/fastutil/objects/Object2ObjectMaps;fastIterable(Lit/unimi/dsi/fastutil/objects/Object2ObjectMap;)Lit/unimi/dsi/fastutil/objects/ObjectIterable;";
    static final String IS_SUPERTYPE_OF =
            "Lcom/google/common/reflect/TypeToken;isSupertypeOf(Lcom/google/common/reflect/TypeToken;)Z";
    static final String MEMOIZE =
            "Lcom/google/common/base/Suppliers;memoize(Lcom/google/common/base/Supplier;)Lcom/google/common/base/Supplier;";
    static final String TO_IMMUTABLE_MAP =
            "Lcom/google/common/collect/ImmutableMap;toImmutableMap(Ljava/util/function/Function;Ljava/util/function/Function;)Ljava/util/stream/Collector;";

    private Targets() {}
}
