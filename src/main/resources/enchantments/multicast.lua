return {
    api_version = 1,
    on_missile_count = function(context)
        return context.parameters.missile_count
    end,
    on_missile_damage_factor = function(context)
        return context.parameters.missile_damage_factor
    end
}
