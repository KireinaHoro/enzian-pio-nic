return function(ctx)
local _ENV = setmetatable(ctx, { __index = _G })

local function event_name(event_id)
    if trace_map == nil or trace_map.payload_formats == nil then
        return string.format("event_%d", event_id)
    end
    local fmt = trace_map.payload_formats.lauberhorn_event
    if fmt == nil or fmt.events == nil then
        return string.format("event_%d", event_id)
    end
    return fmt.events[tostring(event_id)] or string.format("event_%d", event_id)
end

local function event_data_fields(event_id)
    if trace_map == nil or trace_map.payload_formats == nil then
        return {}
    end
    local fmt = trace_map.payload_formats.lauberhorn_event
    if fmt == nil or fmt.event_data == nil then
        return {}
    end
    local data = fmt.event_data[tostring(event_id)] or {}
    if #data > 0 and type(data[1]) == "table" then
        return data
    end

    local offset = 0
    if fmt.fields ~= nil then
        for name, field in pairs(fmt.fields) do
            if name == "event_id" or name == "reserved_event_id" then
                local field_end = tonumber(field.offset) + tonumber(field.width)
                if field_end > offset then
                    offset = field_end
                end
            end
        end
    end

    local fields = {}
    for _, name in ipairs(data) do
        local key = fmt.trace_data_keys ~= nil and fmt.trace_data_keys[name] or nil
        if key ~= nil then
            local width = tonumber(key.width)
            table.insert(fields, { name = name, offset = offset, width = width })
            offset = offset + width
        end
    end
    return fields
end

local function event_name_from_payload(payload_tvb)
    local fields = fields_for_type("lauberhorn_event") or {}
    local id_field = fields.event_id or { offset = 0, width = 6 }
    if not tvb_has_bits(payload_tvb, tonumber(id_field.offset), tonumber(id_field.width)) then
        return "malformed_event"
    end
    return event_name(extract_bits_le(payload_tvb, 0, id_field.offset, id_field.width))
end

local function format_event_data(values)
    if #values == 0 then
        return ""
    end
    local parts = {}
    for _, value in ipairs(values) do
        table.insert(parts, tostring(value.name) .. "=" .. tostring(value.value))
    end
    return table.concat(parts, " ")
end

local function dissect_dcs(payload_tvb, tree)
    local fields = fields_for_type("dcs_event") or {}
    local valid, reason = validate_payload_fields(payload_tvb, fields, { "error", "cli", "state", "action", "request" }, "dcs_event")
    if not valid then
        return malformed_payload_result(tree, payload_tvb, "DCS", reason)
    end

    local error_value = extract_bits_le(payload_tvb, 0, fields.error.offset, fields.error.width)
    local cli = extract_bits_le(payload_tvb, 0, fields.cli.offset, fields.cli.width)
    local state = extract_bits_le(payload_tvb, 0, fields.state.offset, fields.state.width)
    local action = extract_bits_le(payload_tvb, 0, fields.action.offset, fields.action.width)
    local request = extract_bits_le(payload_tvb, 0, fields.request.offset, fields.request.width)
    local request_name = enum_name("dcs_event", "request", request) or tostring(request)
    local action_name = enum_name("dcs_event", "action", action) or tostring(action)
    local state_name = enum_name("dcs_event", "state", state) or tostring(state)
    local cli_low_bits = bit_range_num(cli, 6, 0)
    local unaliased = unalias_address(cli)

    append_enum(tree:add(df.request, byte_range_for_bits(payload_tvb, fields.request.offset, fields.request.width), request), "dcs_event", "request", request)
    append_enum(tree:add(df.action, byte_range_for_bits(payload_tvb, fields.action.offset, fields.action.width), action), "dcs_event", "action", action)
    append_enum(tree:add(df.state, byte_range_for_bits(payload_tvb, fields.state.offset, fields.state.width), state), "dcs_event", "state", state)
    tree:add(df.unaliased_addr, byte_range_for_bits(payload_tvb, fields.cli.offset, fields.cli.width), fmt_addr(unaliased))
    local cli_item = tree:add(df.cli, byte_range_for_bits(payload_tvb, fields.cli.offset, fields.cli.width), fmt_addr(cli))
    if cli_low_bits ~= 0 then
        cli_item:add_proto_expert_info(
            de.cli_low_bits_nonzero,
            string.format("DCS CLI lower address bits are nonzero: 0x%02x", cli_low_bits)
        )
    end
    tree:add(df.error, byte_range_for_bits(payload_tvb, fields.error.offset, fields.error.width), error_value)

    return {
        info = request_name .. ": " .. action_name .. " => " .. state_name,
        dest = fmt_addr(unaliased),
    }
end
local function dissect_event(payload_tvb, tree, pinfo, source_info)
    local fields = fields_for_type("lauberhorn_event") or {}
    local id_field = fields.event_id or { offset = 0, width = 6 }
    if not tvb_has_bits(payload_tvb, tonumber(id_field.offset), tonumber(id_field.width)) then
        return malformed_payload_result(tree, payload_tvb, "event", "event_id extends past payload")
    end

    local event_id = extract_bits_le(payload_tvb, 0, id_field.offset, id_field.width)
    local name = event_name(event_id)
    tree:add(evf.event_name, byte_range_for_bits(payload_tvb, id_field.offset, id_field.width), name)
    tree:add(evf.event_id, byte_range_for_bits(payload_tvb, id_field.offset, id_field.width), event_id):append_text(" (" .. name .. ")")

    local data_fields = event_data_fields(event_id)
    local data_values = {}
    for _, field in ipairs(data_fields) do
        local offset = tonumber(field.offset)
        local width = tonumber(field.width)
        if tvb_has_bits(payload_tvb, offset, width) then
            local value = extract_bits_le(payload_tvb, 0, offset, width)
            local value_range = byte_range_for_bits(payload_tvb, offset, width)
            table.insert(data_values, { name = field.name, value = value, range = value_range })
            local value_text = tostring(field.name) .. "=" .. tostring(value)
            tree:add(evf.trace_data_value, value_range, value_text)
        else
            local item = tree:add(evf.trace_data_value, anchor_range(payload_tvb),
                "malformed " .. tostring(field.name) .. ": extends past event payload")
            item.generated = true
        end
    end

    local data_info = format_event_data(data_values)
    tree:add(evf.trace_data, full_range(payload_tvb), data_info ~= "" and data_info or "none")
    local info = name
    if data_info ~= "" then
        info = info .. " " .. data_info
    end

    local flow_info = add_flow_fields(tree, pinfo, event_flow(data_values, name, source_info, pinfo))
    if flow_info ~= "" then
        info = info .. " [" .. flow_info .. "]"
    end

    return {
        info = info,
        source = name,
        dest = flow_info,
    }
end

ctx.event_name = event_name
ctx.event_data_fields = event_data_fields
ctx.event_name_from_payload = event_name_from_payload
ctx.format_event_data = format_event_data
ctx.dissect_dcs = dissect_dcs
ctx.dissect_event = dissect_event

end
