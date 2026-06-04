return function(ctx)
local _ENV = setmetatable(ctx, { __index = _G })

trace_map = nil
sources_by_id = {}
id_kinds = {}
id_kind_order = {}
flow_kinds = {}
flow_kind_order = {}
correlation_relationships = {}
direction_rules = {}

local function static_fields_for_flow_kind(flow_kind)
    local field_key = "flow_" .. tostring(flow_kind):gsub("[^%w_]", "_")
    return {
        field = f[field_key],
        flow_id_field = f[field_key .. "_id"],
        raw_field = f[field_key .. "_raw_id"],
        generation_field = f[field_key .. "_generation"],
    }
end

local function load_trace_correlation()
    local metadata = trace_map ~= nil and trace_map.trace_correlation or nil
    if metadata == nil then
        id_kinds = {}
        id_kind_order = {}
        flow_kinds = {}
        flow_kind_order = {}
        correlation_relationships = {}
        direction_rules = {}
        return
    end

    id_kinds = {}
    id_kind_order = {}
    flow_kinds = {}
    flow_kind_order = {}
    correlation_relationships = metadata.relationships or {}
    direction_rules = metadata.directions or {}

    for flow_kind, spec in pairs(metadata.flow_kinds or {}) do
        local binding = static_fields_for_flow_kind(flow_kind)
        flow_kinds[flow_kind] = {
            id_kind = spec.id_kind,
            label = spec.label or flow_kind,
            title = spec.title or (flow_kind .. "Flow"),
            field = binding.field,
            flow_id_field = binding.flow_id_field,
            raw_field = binding.raw_field,
            generation_field = binding.generation_field,
        }
        append_unique(flow_kind_order, flow_kind)
    end

    for id_kind, spec in pairs(metadata.id_kinds or {}) do
        id_kinds[id_kind] = {
            flow_kind = spec.flow_kind,
            invalid = spec.invalid,
            wrap = spec.wrap ~= false,
        }
        append_unique(id_kind_order, id_kind)
    end
end

local function load_trace_map(json_text, keep_analysis)
    trace_map = json_decode(json_text)
    sources_by_id = {}
    if trace_map ~= nil and trace_map.sources ~= nil then
        for _, src in ipairs(trace_map.sources) do
            sources_by_id[tonumber(src.source)] = src
        end
    end
    load_trace_correlation()
    if not keep_analysis then
        reset_flows()
        reset_eci_pairs()
    end
end
local function trace_data_key_width(name)
    if trace_map == nil or trace_map.payload_formats == nil then
        return nil
    end
    local fmt = trace_map.payload_formats.lauberhorn_event
    if fmt == nil or fmt.trace_data_keys == nil or fmt.trace_data_keys[name] == nil then
        return nil
    end
    return tonumber(fmt.trace_data_keys[name].width)
end
local function source_label(src, source)
    if src == nil then
        return tostring(source)
    end
    if src.type == "dcs_event" then
        if src.dcs ~= nil and src.local_source ~= nil then
            return tostring(src.dcs) .. "_" .. tostring(src.local_source)
        end
    elseif src.type == "eci" then
        local label = src.channel or src.port or tostring(source)
        if src.clock_domain == "app" or src.clock_domain == "sys" then
            return label .. " (" .. src.clock_domain .. ")"
        end
        return label
    elseif src.type == "lauberhorn_event" then
        return src.event_name or src.port or tostring(source)
    end
    return src.port or tostring(source)
end

local function add_source_info(subtree, tvb, source, parsed_name)
    local src = sources_by_id[source]
    if src == nil then
        return { type = "unknown" }
    end
    local source_tree = subtree:add(f.source_name, tvb(40, 2), parsed_name or source_label(src, source))
    source_tree:add(sf.source_type, tvb(32, 2), src.type or "")
    source_tree:add(sf.source_port, tvb(32, 2), src.port or "")
    source_tree:add(sf.clock_domain, tvb(32, 2), src.clock_domain or "")
    source_tree:add(sf.dcs, tvb(32, 2), src.dcs or "")
    source_tree:add(sf.channel, tvb(32, 2), src.channel or "")
    if src.local_source ~= nil then
        source_tree:add(sf.local_source, tvb(32, 2), tonumber(src.local_source))
    end
    return src
end

local function fields_for_type(source_type)
    if trace_map == nil or trace_map.payload_formats == nil then
        return nil
    end
    local fmt = trace_map.payload_formats[source_type]
    if fmt == nil then
        return nil
    end
    return fmt.fields
end

local function enum_name(source_type, field_name, value)
    if trace_map == nil or trace_map.payload_formats == nil then
        return nil
    end
    local fmt = trace_map.payload_formats[source_type]
    if fmt == nil or fmt.fields == nil then
        return nil
    end
    local field = fmt.fields[field_name]
    if field == nil or field.enum == nil then
        return nil
    end
    return field.enum[tostring(value)]
end

local function append_enum(item, source_type, field_name, value)
    local name = enum_name(source_type, field_name, value)
    if name ~= nil then
        item:append_text(" (" .. name .. ")")
    end
end

ctx.load_trace_correlation = load_trace_correlation
ctx.load_trace_map = load_trace_map
ctx.trace_data_key_width = trace_data_key_width
ctx.source_label = source_label
ctx.add_source_info = add_source_info
ctx.fields_for_type = fields_for_type
ctx.enum_name = enum_name
ctx.append_enum = append_enum

end
