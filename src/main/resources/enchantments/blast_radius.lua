return {
    api_version = 1,
    on_blast_radius_factor = function(context)
        local increment = math.max(0, math.min(1 / 3, context.parameters.increment))
        return math.max(1, math.min(2, 1 + context.enchantment.level * increment))
    end
}
