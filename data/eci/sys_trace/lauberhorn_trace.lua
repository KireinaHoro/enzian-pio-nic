local lhtrace = Proto("lhtrace", "Lauberhorn Trace Dispatcher")
local lhmeta = Proto("lhtrace_meta", "Trace Metadata")
local lhctrl = Proto("lhtrace_ctrl", "Trace Control")
local lhdcs = Proto("lhtrace_dcs", "DC")
local lheci_app = Proto("lhtrace_eci_app", "ECI app")
local lheci_sys = Proto("lhtrace_eci_sys", "ECI sys")
local lhevent = Proto("lhtrace_event", "Lauberhorn Events")

local KIND_METADATA = 1
local KIND_LOST = 3
local KIND_BUBBLE = 4

local kind_names = {
    [1] = "metadata",
    [2] = "sample",
    [3] = "lost",
    [4] = "bubble",
}

local packet_protocols = {
    LHTM = { proto = lhmeta, col = "Trace Metadata", title = "Trace Metadata", family = "metadata" },
    LHTC = { proto = lhctrl, col = "Trace Control", title = "Trace Control", family = "control" },
    LHTE = { proto = lhevent, col = "Lauberhorn Events", title = "Lauberhorn Events", family = "event" },
    LHTD = { proto = lhdcs, col = "DC", title = "DC", family = "dcs" },
    LHEA = { proto = lheci_app, col = "ECI", title = "ECI", family = "eci" },
    LHES = { proto = lheci_sys, col = "ECI", title = "ECI", family = "eci" },
}

local f = lhtrace.fields
f.magic = ProtoField.string("lhtrace.magic", "Magic")
f.version = ProtoField.uint8("lhtrace.version", "Version", base.DEC)
f.kind = ProtoField.uint8("lhtrace.kind", "Kind", base.DEC, kind_names)
f.flags = ProtoField.uint16("lhtrace.flags", "Flags", base.HEX)
f.sample = ProtoField.uint64("lhtrace.sample", "Sample", base.DEC)
f.physical_sample = ProtoField.uint64("lhtrace.physical_sample", "Physical Sample", base.DEC)
f.timestamp = ProtoField.uint64("lhtrace.timestamp", "Adjusted Timestamp", base.DEC)
f.raw_timestamp = ProtoField.uint64("lhtrace.raw_timestamp", "Raw Timestamp", base.DEC)
f.source = ProtoField.uint16("lhtrace.source", "Source", base.DEC)
f.lost_count = ProtoField.uint32("lhtrace.lost_count", "Lost Count", base.DEC)
f.payload_len = ProtoField.uint32("lhtrace.payload_len", "Payload Length", base.DEC)
f.payload = ProtoField.bytes("lhtrace.payload", "Payload")
f.source_name = ProtoField.string("lhtrace.source.name", "Source")
f.flow = ProtoField.string("lhtrace.flow", "Flow")
f.flow_id = ProtoField.uint32("lhtrace.flow.id", "Correlation Component ID", base.DEC)
f.flow_type = ProtoField.string("lhtrace.flow.type", "Flow Type")
f.flow_packet = ProtoField.string("lhtrace.flow.packet", "Packet Flow")
f.flow_packet_id = ProtoField.uint32("lhtrace.flow.packet.id", "Packet Flow ID", base.DEC)
f.flow_packet_raw_id = ProtoField.uint32("lhtrace.flow.packet.raw_id", "PacketID", base.DEC)
f.flow_packet_generation = ProtoField.uint32("lhtrace.flow.packet.generation", "PacketID Generation", base.DEC)
f.flow_rpc = ProtoField.string("lhtrace.flow.rpc", "RPC Flow")
f.flow_rpc_id = ProtoField.uint32("lhtrace.flow.rpc.id", "RPC Flow ID", base.DEC)
f.flow_rpc_raw_id = ProtoField.uint32("lhtrace.flow.rpc.raw_id", "RpcID", base.DEC)
f.flow_rpc_generation = ProtoField.uint32("lhtrace.flow.rpc.generation", "RpcID Generation", base.DEC)
f.flow_host_msg = ProtoField.string("lhtrace.flow.host_msg", "Host Message Flow")
f.flow_host_msg_id = ProtoField.uint32("lhtrace.flow.host_msg.id", "Host Message Flow ID", base.DEC)
f.flow_host_msg_raw_id = ProtoField.uint32("lhtrace.flow.host_msg.raw_id", "HostMsgID", base.DEC)
f.flow_host_msg_generation = ProtoField.uint32("lhtrace.flow.host_msg.generation", "HostMsgID Generation", base.DEC)

local mf = lhmeta.fields
mf.json = ProtoField.string("lhtrace.meta.json", "Trace Map JSON")

local sf = lhtrace.fields
sf.source_type = ProtoField.string("lhtrace.source.type", "Source Type")
sf.source_port = ProtoField.string("lhtrace.source.port", "Source Port")
sf.clock_domain = ProtoField.string("lhtrace.source.clock_domain", "Clock Domain")
sf.dcs = ProtoField.string("lhtrace.source.dcs", "DCS")
sf.channel = ProtoField.string("lhtrace.source.channel", "Channel")
sf.local_source = ProtoField.uint16("lhtrace.source.local", "Local Source", base.DEC)

local df = lhdcs.fields
df.error = ProtoField.uint8("lhtrace.dcs.error", "Error", base.DEC)
df.cli = ProtoField.string("lhtrace.dcs.cli", "CLI")
df.state = ProtoField.uint8("lhtrace.dcs.state", "State", base.DEC)
df.action = ProtoField.uint8("lhtrace.dcs.action", "Action", base.DEC)
df.request = ProtoField.uint8("lhtrace.dcs.request", "Request", base.DEC)
df.unaliased_addr = ProtoField.string("lhtrace.dcs.unaliased_addr", "Unaliased Address")

local ef = lheci_app.fields
ef.header = ProtoField.uint64("lhtrace.eci.header", "Header", base.HEX)
ef.opcode = ProtoField.uint8("lhtrace.eci.opcode", "Opcode", base.DEC)
ef.vc = ProtoField.uint8("lhtrace.eci.vc", "VC", base.DEC)
ef.accepted = ProtoField.uint8("lhtrace.eci.accepted", "Accepted", base.DEC)
ef.phase = ProtoField.string("lhtrace.eci.phase", "Phase")
ef.message = ProtoField.string("lhtrace.eci.message", "Message")
ef.unaliased_addr = ProtoField.string("lhtrace.eci.unaliased_addr", "Unaliased Address")

local evf = lhevent.fields
evf.event_id = ProtoField.uint16("lhtrace.event.id", "Event ID", base.DEC)
evf.event_name = ProtoField.string("lhtrace.event.name", "Event Name")
evf.trace_data = ProtoField.string("lhtrace.event.trace_data", "Trace Data")
evf.trace_data_value = ProtoField.string("lhtrace.event.trace_data.value", "Trace Data Value")

local HEADER_LEN = 50
local trace_map = nil
local sources_by_id = {}
local flow_state = {}
local frame_flow_cache = {}

local id_kinds = {}
local id_kind_order = {}
local flow_kinds = {}
local flow_kind_order = {}
local correlation_relationships = {}
local direction_rules = {}

local function reset_flows()
    local next_flow_id = {}
    for _, flow_kind in ipairs(flow_kind_order) do
        next_flow_id[flow_kind] = 1
    end
    flow_state = {
        nodes = {},
        nodes_by_key = {},
        id_state = {},
        next_node_id = 1,
        next_component_id = 1,
        next_flow_id = next_flow_id,
    }
    frame_flow_cache = {}
end

reset_flows()

local function json_decode(text)
    local pos = 1

    local function skip_ws()
        while true do
            local c = text:sub(pos, pos)
            if c == " " or c == "\n" or c == "\r" or c == "\t" then
                pos = pos + 1
            else
                return
            end
        end
    end

    local parse_value

    local function parse_string()
        pos = pos + 1
        local out = {}
        while pos <= #text do
            local c = text:sub(pos, pos)
            if c == '"' then
                pos = pos + 1
                return table.concat(out)
            elseif c == "\\" then
                local esc = text:sub(pos + 1, pos + 1)
                local map = { ['"'] = '"', ["\\"] = "\\", ["/"] = "/", b = "\b", f = "\f", n = "\n", r = "\r", t = "\t" }
                if esc == "u" then
                    table.insert(out, "?")
                    pos = pos + 6
                else
                    table.insert(out, map[esc] or esc)
                    pos = pos + 2
                end
            else
                table.insert(out, c)
                pos = pos + 1
            end
        end
        error("unterminated JSON string")
    end

    local function parse_number()
        local start = pos
        while text:sub(pos, pos):match("[0-9eE+%-%.]") do
            pos = pos + 1
        end
        return tonumber(text:sub(start, pos - 1))
    end

    local function parse_array()
        pos = pos + 1
        local arr = {}
        skip_ws()
        if text:sub(pos, pos) == "]" then
            pos = pos + 1
            return arr
        end
        while true do
            table.insert(arr, parse_value())
            skip_ws()
            local c = text:sub(pos, pos)
            pos = pos + 1
            if c == "]" then
                return arr
            elseif c ~= "," then
                error("expected JSON array delimiter")
            end
        end
    end

    local function parse_object()
        pos = pos + 1
        local obj = {}
        skip_ws()
        if text:sub(pos, pos) == "}" then
            pos = pos + 1
            return obj
        end
        while true do
            skip_ws()
            local key = parse_string()
            skip_ws()
            if text:sub(pos, pos) ~= ":" then
                error("expected JSON object colon")
            end
            pos = pos + 1
            obj[key] = parse_value()
            skip_ws()
            local c = text:sub(pos, pos)
            pos = pos + 1
            if c == "}" then
                return obj
            elseif c ~= "," then
                error("expected JSON object delimiter")
            end
        end
    end

    function parse_value()
        skip_ws()
        local c = text:sub(pos, pos)
        if c == '"' then return parse_string() end
        if c == "{" then return parse_object() end
        if c == "[" then return parse_array() end
        if c == "t" and text:sub(pos, pos + 3) == "true" then pos = pos + 4; return true end
        if c == "f" and text:sub(pos, pos + 4) == "false" then pos = pos + 5; return false end
        if c == "n" and text:sub(pos, pos + 3) == "null" then pos = pos + 4; return nil end
        return parse_number()
    end

    return parse_value()
end

local function append_unique(list, value)
    for _, existing in ipairs(list) do
        if existing == value then
            return
        end
    end
    table.insert(list, value)
end

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

local function load_trace_map(json_text)
    trace_map = json_decode(json_text)
    sources_by_id = {}
    if trace_map ~= nil and trace_map.sources ~= nil then
        for _, src in ipairs(trace_map.sources) do
            sources_by_id[tonumber(src.source)] = src
        end
    end
    load_trace_correlation()
    reset_flows()
end

local function extract_bits_le(tvb, base_offset, bit_offset, width)
    local value = 0
    local factor = 1
    for bit = 0, width - 1 do
        local absolute = bit_offset + bit
        local byte = tvb(base_offset + math.floor(absolute / 8), 1):uint()
        local bit_value = math.floor(byte / (2 ^ (absolute % 8))) % 2
        value = value + bit_value * factor
        factor = factor * 2
    end
    return value
end

local function byte_range_for_bits(tvb, bit_offset, width)
    local byte_offset = math.floor(bit_offset / 8)
    local byte_len = math.ceil(((bit_offset % 8) + width) / 8)
    return tvb(byte_offset, byte_len)
end

local function tvb_has_bits(tvb, bit_offset, width)
    if bit_offset == nil or width == nil then
        return false
    end
    return math.ceil((bit_offset + width) / 8) <= tvb:len()
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

local function invalid_trace_id(name)
    local spec = id_kinds[name]
    if spec == nil or spec.invalid ~= "all_ones" then
        return nil
    end
    local width = trace_data_key_width(name)
    if width == nil then
        return nil
    end
    return (2 ^ width) - 1
end

local function infer_event_direction(event_name, source_info)
    local haystack = string.lower(table.concat({
        event_name or "",
        source_info ~= nil and (source_info.name or "") or "",
        source_info ~= nil and (source_info.port or "") or "",
    }, " "))

    for _, rule in ipairs(direction_rules) do
        for _, needle in ipairs(rule.contains or {}) do
            if haystack:find(string.lower(tostring(needle)), 1, true) ~= nil then
                return tostring(rule.direction)
            end
        end
    end
    return "unknown"
end

local function id_namespace(kind, direction)
    return tostring(kind) .. ":" .. tostring(direction)
end

local function id_instance_state(kind, direction)
    local namespace = id_namespace(kind, direction)
    local state = flow_state.id_state[namespace]
    if state == nil then
        local width = trace_data_key_width(kind) or 0
        local spec = id_kinds[kind] or {}
        state = {
            last_value = nil,
            generation = 0,
            modulus = 2 ^ width,
            wrap = spec.wrap ~= false,
        }
        flow_state.id_state[namespace] = state
    end
    return state
end

local function generation_for_id(kind, direction, value)
    local state = id_instance_state(kind, direction)
    if state.wrap and state.last_value ~= nil and value ~= state.last_value then
        local high_mark = math.floor(state.modulus * 3 / 4)
        local low_mark = math.floor(state.modulus / 4)
        if value < state.last_value and state.last_value >= high_mark and value <= low_mark then
            state.generation = state.generation + 1
        end
    end
    state.last_value = value
    return state.generation
end

local function find_node(node)
    if node.parent ~= node then
        node.parent = find_node(node.parent)
    end
    return node.parent
end

local function component_id(root)
    if root.component_id == nil then
        root.component_id = flow_state.next_component_id
        flow_state.next_component_id = flow_state.next_component_id + 1
    end
    return root.component_id
end

local function make_id_node(kind, direction, value)
    local generation = generation_for_id(kind, direction, value)
    local key = table.concat({ kind, direction, tostring(generation), tostring(value) }, ":")
    local node = flow_state.nodes_by_key[key]
    if node ~= nil then
        return node
    end

    node = {
        id = flow_state.next_node_id,
        parent = nil,
        rank = 0,
        kind = kind,
        direction = direction,
        value = value,
        generation = generation,
        ids = {},
        flow_ids = {},
    }
    node.parent = node
    node.ids[kind] = { node }
    flow_state.next_node_id = flow_state.next_node_id + 1
    flow_state.nodes_by_key[key] = node
    table.insert(flow_state.nodes, node)
    return node
end

local function merge_node_lists(to_root, from_root)
    for kind, nodes in pairs(from_root.ids) do
        if to_root.ids[kind] == nil then
            to_root.ids[kind] = {}
        end
        for _, node in ipairs(nodes) do
            table.insert(to_root.ids[kind], node)
        end
    end
    for kind, id in pairs(from_root.flow_ids) do
        if to_root.flow_ids[kind] == nil then
            to_root.flow_ids[kind] = id
        end
    end
end

local function union_nodes(left, right)
    local left_root = find_node(left)
    local right_root = find_node(right)
    if left_root == right_root then
        return left_root
    end
    if left_root.rank < right_root.rank then
        left_root, right_root = right_root, left_root
    end
    right_root.parent = left_root
    if left_root.rank == right_root.rank then
        left_root.rank = left_root.rank + 1
    end
    if left_root.component_id == nil then
        left_root.component_id = right_root.component_id
    end
    merge_node_lists(left_root, right_root)
    return left_root
end

local function flow_id_for_kind(root, kind)
    root = find_node(root)
    if root.ids[kind] == nil then
        return nil
    end
    local spec = id_kinds[kind]
    if spec == nil or spec.flow_kind == nil then
        return nil
    end
    local flow_kind = spec.flow_kind
    if root.flow_ids[flow_kind] == nil then
        root.flow_ids[flow_kind] = flow_state.next_flow_id[flow_kind] or 1
        flow_state.next_flow_id[flow_kind] = root.flow_ids[flow_kind] + 1
    end
    return root.flow_ids[flow_kind]
end

local function id_token_label(node)
    return tostring(node.direction) .. ":" .. tostring(node.value) .. "." .. tostring(node.generation)
end

local function flow_label(root, kind)
    local id_spec = id_kinds[kind]
    if id_spec == nil then
        return nil
    end
    local spec = flow_kinds[id_spec.flow_kind]
    if spec == nil then
        return nil
    end
    local id = flow_id_for_kind(root, kind)
    if id == nil then
        return nil
    end

    local parts = {}
    for _, node in ipairs(find_node(root).ids[kind] or {}) do
        table.insert(parts, id_token_label(node))
    end
    return spec.title .. "#" .. tostring(id) .. "[" .. table.concat(parts, ",") .. "]"
end

local function event_flow(data_values, event_name, source_info, pinfo)
    local frame_number = pinfo ~= nil and tonumber(pinfo.number) or nil
    if pinfo ~= nil and pinfo.visited and frame_number ~= nil and frame_flow_cache[frame_number] ~= nil then
        return frame_flow_cache[frame_number]
    end

    local direction = infer_event_direction(event_name, source_info)
    local nodes = {}
    local nodes_by_kind = {}
    local first_range = nil
    for _, value in ipairs(data_values) do
        if id_kinds[value.name] ~= nil then
            local invalid_id = invalid_trace_id(value.name)
            if invalid_id == nil or value.value ~= invalid_id then
                local node = make_id_node(value.name, direction, value.value)
                table.insert(nodes, node)
                if nodes_by_kind[value.name] == nil then
                    nodes_by_kind[value.name] = {}
                end
                table.insert(nodes_by_kind[value.name], node)
                first_range = first_range or value.range
            end
        end
    end

    if #nodes == 0 then
        if frame_number ~= nil then
            frame_flow_cache[frame_number] = nil
        end
        return nil
    end

    local root = nodes[1]
    for _, relationship in ipairs(correlation_relationships) do
        if relationship.type == "same_event_union" then
            local relationship_nodes = {}
            for _, kind in ipairs(relationship.id_kinds or {}) do
                for _, node in ipairs(nodes_by_kind[kind] or {}) do
                    table.insert(relationship_nodes, node)
                end
            end
            if #relationship_nodes > 0 then
                local relationship_root = relationship_nodes[1]
                for idx = 2, #relationship_nodes do
                    relationship_root = union_nodes(relationship_root, relationship_nodes[idx])
                end
                root = union_nodes(root, relationship_root)
            end
        end
    end
    root = find_node(root)
    component_id(root)

    local result = {
        root = root,
        nodes = nodes,
        range = first_range,
    }
    if frame_number ~= nil then
        frame_flow_cache[frame_number] = result
    end
    return result
end

local function add_flow_fields(tree, pinfo, flow)
    if flow == nil then
        return ""
    end

    local root = find_node(flow.root)
    local range = flow.range
    local labels = {}
    for _, kind in ipairs(id_kind_order) do
        local label = flow_label(root, kind)
        if label ~= nil then
            table.insert(labels, label)
        end
    end
    if #labels == 0 then
        return ""
    end

    local summary = table.concat(labels, " ")
    local flow_tree = tree:add(f.flow, range, summary)
    flow_tree:add(f.flow_id, range, component_id(root))
    flow_tree:add(f.flow_type, range, "transitive_id")

    for _, kind in ipairs(id_kind_order) do
        local id_spec = id_kinds[kind] or {}
        local spec = flow_kinds[id_spec.flow_kind]
        local label = flow_label(root, kind)
        if label ~= nil then
            local kind_tree = spec.field ~= nil and flow_tree:add(spec.field, range, label) or flow_tree:add(f.flow, range, label)
            if spec.flow_id_field ~= nil then
                kind_tree:add(spec.flow_id_field, range, flow_id_for_kind(root, kind))
            end
            for _, node in ipairs(root.ids[kind] or {}) do
                if spec.raw_field ~= nil then
                    kind_tree:add(spec.raw_field, range, node.value)
                end
                if spec.generation_field ~= nil then
                    kind_tree:add(spec.generation_field, range, node.generation)
                end
            end
        end
    end

    if pinfo ~= nil then
        pinfo.cols.dst = summary
    end
    return summary
end

local function bit_range_num(value, hi, lo)
    return math.floor(value / (2 ^ lo)) % (2 ^ (hi - lo + 1))
end

local function unalias_cacheline_index(aliased_cli)
    local cli = 0
    cli = cli + bit_range_num(aliased_cli, 32, 13) * (2 ^ 13)
    cli = cli + bit32.bxor(bit_range_num(aliased_cli, 12, 8), bit_range_num(aliased_cli, 17, 13)) * (2 ^ 8)
    cli = cli + bit32.bxor(bit_range_num(aliased_cli, 7, 5), bit_range_num(aliased_cli, 20, 18)) * (2 ^ 5)
    cli = cli + bit32.bxor(
        bit_range_num(aliased_cli, 4, 3)
        , bit_range_num(aliased_cli, 19, 18)
        , bit_range_num(aliased_cli, 17, 16)
        , bit_range_num(aliased_cli, 6, 5)
    ) * (2 ^ 3)
    cli = cli + bit32.bxor(
        bit_range_num(aliased_cli, 2, 0)
        , bit_range_num(aliased_cli, 20, 18)
        , bit_range_num(aliased_cli, 15, 13)
        , bit_range_num(aliased_cli, 7, 5)
    )
    return cli
end

local function unalias_address(aliased_addr)
    return unalias_cacheline_index(bit_range_num(aliased_addr, 39, 7)) * 128
end

local function fmt_addr(value)
    return string.format("0x%010x", value)
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
    local source_tree = subtree:add(f.source_name, tvb(32, 2), parsed_name or source_label(src, source))
    source_tree:add_le(f.source, tvb(32, 2))
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

local function eci_class(source_info)
    local local_source = tonumber(source_info.local_source)
    if local_source == 0 then
        return "mreq"
    elseif local_source ~= nil and local_source >= 1 and local_source <= 4 then
        return "mrsp"
    elseif local_source == 5 then
        return "mfwd"
    end
    return nil
end

local function eci_opcode_name(source_info, opcode)
    if trace_map == nil or trace_map.payload_formats == nil then
        return nil
    end
    local fmt = trace_map.payload_formats.eci
    if fmt == nil or fmt.opcode_enums == nil then
        return nil
    end
    local class = eci_class(source_info)
    if class == nil or fmt.opcode_enums[class] == nil then
        return nil
    end
    return fmt.opcode_enums[class][tostring(opcode)]
end

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
    local error_value = extract_bits_le(payload_tvb, 0, fields.error.offset, fields.error.width)
    local cli = extract_bits_le(payload_tvb, 0, fields.cli.offset, fields.cli.width)
    local state = extract_bits_le(payload_tvb, 0, fields.state.offset, fields.state.width)
    local action = extract_bits_le(payload_tvb, 0, fields.action.offset, fields.action.width)
    local request = extract_bits_le(payload_tvb, 0, fields.request.offset, fields.request.width)
    local request_name = enum_name("dcs_event", "request", request) or tostring(request)
    local action_name = enum_name("dcs_event", "action", action) or tostring(action)
    local state_name = enum_name("dcs_event", "state", state) or tostring(state)
    local unaliased = unalias_cacheline_index(cli) * 128

    append_enum(tree:add(df.request, byte_range_for_bits(payload_tvb, fields.request.offset, fields.request.width), request), "dcs_event", "request", request)
    append_enum(tree:add(df.action, byte_range_for_bits(payload_tvb, fields.action.offset, fields.action.width), action), "dcs_event", "action", action)
    append_enum(tree:add(df.state, byte_range_for_bits(payload_tvb, fields.state.offset, fields.state.width), state), "dcs_event", "state", state)
    tree:add(df.unaliased_addr, byte_range_for_bits(payload_tvb, fields.cli.offset, fields.cli.width), fmt_addr(unaliased))
    tree:add(df.cli, byte_range_for_bits(payload_tvb, fields.cli.offset, fields.cli.width), fmt_addr(cli))
    tree:add(df.error, byte_range_for_bits(payload_tvb, fields.error.offset, fields.error.width), error_value)

    return {
        info = request_name .. ": " .. action_name .. " => " .. state_name,
        dest = fmt_addr(unaliased),
    }
end

local function dissect_eci(payload_tvb, tree, source_info)
    local fields = fields_for_type("eci") or {}
    local opcode = math.floor(payload_tvb(7, 1):uint() / 8)
    local header = extract_bits_le(payload_tvb, 0, fields.eci_header.offset, fields.eci_header.width)
    local opcode_item = tree:add(ef.opcode, payload_tvb(7, 1), opcode)
    local message = eci_opcode_name(source_info, opcode)
    if message ~= nil then
        opcode_item:append_text(" (" .. message .. ")")
        tree:add(ef.message, payload_tvb(0, 0), message)
    end
    local vc = extract_bits_le(payload_tvb, 0, fields.vc.offset, fields.vc.width)
    local accepted = extract_bits_le(payload_tvb, 0, fields.accepted.offset, fields.accepted.width)
    local phase = accepted ~= 0 and "accepted" or "valid"
    local aliased_addr
    if eci_class(source_info) == "mrsp" and (opcode == 9 or opcode == 10) then
        aliased_addr = extract_bits_le(payload_tvb, 0, 7, 33) * 128
    else
        aliased_addr = extract_bits_le(payload_tvb, 0, 0, 40)
    end
    local unaliased = unalias_address(aliased_addr)
    tree:add(ef.unaliased_addr, byte_range_for_bits(payload_tvb, 0, 40), fmt_addr(unaliased))
    tree:add(ef.vc, byte_range_for_bits(payload_tvb, fields.vc.offset, fields.vc.width), vc)
    tree:add(ef.accepted, byte_range_for_bits(payload_tvb, fields.accepted.offset, fields.accepted.width), accepted)
    tree:add(ef.phase, byte_range_for_bits(payload_tvb, fields.accepted.offset, fields.accepted.width), phase)
    tree:add_le(ef.header, byte_range_for_bits(payload_tvb, fields.eci_header.offset, fields.eci_header.width))

    return {
        info = (message or string.format("opcode_%d", opcode)) .. " " .. phase,
        dest = fmt_addr(unaliased),
    }
end

local function dissect_event(payload_tvb, tree, pinfo, source_info)
    local fields = fields_for_type("lauberhorn_event") or {}
    local id_field = fields.event_id or { offset = 0, width = 6 }
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
            tree:add(evf.trace_data_value, payload_tvb(0, 0),
                "malformed " .. tostring(field.name) .. ": extends past event payload")
        end
    end

    local data_info = format_event_data(data_values)
    tree:add(evf.trace_data, payload_tvb(), data_info ~= "" and data_info or "none")
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

function lhtrace.dissector(tvb, pinfo, tree)
    if tvb:len() < HEADER_LEN then
        return 0
    end
    local magic = tvb(0, 4):string()
    local packet_protocol = packet_protocols[magic]
    if packet_protocol == nil then
        return 0
    end

    local kind = tvb(5, 1):uint()
    local sample = tvb(8, 8):le_uint64()
    local physical_sample = tvb(16, 8):le_uint64()
    local timestamp = tvb(24, 8):le_uint64()
    local raw_timestamp = tvb(32, 8):le_uint64()
    local source = tvb(40, 2):le_uint()
    local lost_count = tvb(42, 4):le_uint()
    local payload_len = tvb(46, 4):le_uint()
    local payload_tvb = nil
    if payload_len > 0 and tvb:len() >= HEADER_LEN + payload_len then
        payload_tvb = tvb(HEADER_LEN, payload_len)
    end

    pinfo.cols.protocol = packet_protocol.col
    pinfo.cols.src = ""
    pinfo.cols.dst = ""

    local info = kind_names[kind] or "unknown"
    local subtree = tree:add(packet_protocol.proto, tvb(), packet_protocol.title)

    if payload_tvb ~= nil then
        if packet_protocol.family == "metadata" then
            local json_text = payload_tvb:string()
            load_trace_map(json_text)
            subtree:add(mf.json, payload_tvb())
            info = "Trace map JSON"
        elseif packet_protocol.family == "dcs" then
            local src = sources_by_id[source] or { type = "unknown" }
            local label = source_label(src, source)
            pinfo.cols.src = label
            add_source_info(subtree, tvb, source, label)
            local parsed = dissect_dcs(payload_tvb, subtree)
            pinfo.cols.dst = parsed.dest
            info = parsed.info
        elseif packet_protocol.family == "eci" then
            local src = sources_by_id[source] or { type = "unknown" }
            local label = source_label(src, source)
            pinfo.cols.src = label
            add_source_info(subtree, tvb, source, label)
            local parsed = dissect_eci(payload_tvb, subtree, src)
            pinfo.cols.dst = parsed.dest
            info = parsed.info
        elseif packet_protocol.family == "event" then
            local label = event_name_from_payload(payload_tvb)
            pinfo.cols.src = label
            add_source_info(subtree, tvb, source, label)
            local parsed = dissect_event(payload_tvb, subtree, pinfo, src)
            info = parsed.info
        else
            subtree:add(f.payload, payload_tvb)
        end
    elseif packet_protocol.family == "control" then
        info = (kind_names[kind] or "control") .. " count=" .. tostring(lost_count)
    end

    pinfo.cols.info = info
    subtree:append_text(": " .. info)

    local raw_tree = subtree:add(tvb(), "Raw Trace Envelope")
    raw_tree:add(f.magic, tvb(0, 4))
    raw_tree:add_le(f.version, tvb(4, 1))
    raw_tree:add_le(f.kind, tvb(5, 1))
    raw_tree:add_le(f.flags, tvb(6, 2))
    raw_tree:add_le(f.sample, tvb(8, 8))
    raw_tree:add_le(f.physical_sample, tvb(16, 8))
    raw_tree:add_le(f.timestamp, tvb(24, 8))
    raw_tree:add_le(f.raw_timestamp, tvb(32, 8))
    raw_tree:add_le(f.source, tvb(40, 2))
    raw_tree:add_le(f.lost_count, tvb(42, 4))
    raw_tree:add_le(f.payload_len, tvb(46, 4))
    if payload_tvb ~= nil and packet_protocol.family ~= "metadata" then
        raw_tree:add(f.payload, payload_tvb)
    end

    return tvb:len()
end

local encap_table = DissectorTable.get("wtap_encap")
if encap_table ~= nil then
    if wtap ~= nil and wtap.USER0 ~= nil then
        encap_table:add(wtap.USER0, lhtrace)
    else
        encap_table:add(147, lhtrace)
    end
end
