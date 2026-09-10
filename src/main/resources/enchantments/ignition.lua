return {
    api_version = 1,
    on_ignition_ticks = function(context)
        return math.max(0, math.min(200, context.parameters.base_ticks
            + context.parameters.ticks_per_level * context.enchantment.level))
    end
}
