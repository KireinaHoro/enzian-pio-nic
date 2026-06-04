return function(ctx)
local _ENV = setmetatable(ctx, { __index = _G })

flow_state = {}
frame_flow_cache = {}

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
    local key = table.concat({ kind, direction, int_text(generation), int_text(value) }, ":")
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
    return tostring(node.direction) .. ":" .. int_text(node.value) .. "." .. int_text(node.generation)
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
    return flow_text(spec.title .. "#" .. int_text(id) .. "[" .. table.concat(parts, ",") .. "]")
end

local function event_flow(data_values, event_name, source_info, pinfo)
    local frame_number = pinfo ~= nil and tonumber(pinfo.number) or nil
    local current_range = nil
    for _, value in ipairs(data_values) do
        if id_kinds[value.name] ~= nil then
            local invalid_id = invalid_trace_id(value.name)
            if invalid_id == nil or value.value ~= invalid_id then
                current_range = value.range
                break
            end
        end
    end

    if pinfo ~= nil and pinfo.visited and frame_number ~= nil and frame_flow_cache[frame_number] ~= nil then
        frame_flow_cache[frame_number].range = current_range
        return frame_flow_cache[frame_number]
    end

    local direction = infer_event_direction(event_name, source_info)
    local nodes = {}
    local nodes_by_kind = {}
    local first_range = current_range
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
    if range == nil then
        if pinfo ~= nil then
            pinfo.cols.dst = summary
        end
        return summary
    end

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

ctx.reset_flows = reset_flows
ctx.event_flow = event_flow
ctx.add_flow_fields = add_flow_fields
ctx.find_flow_node = find_node
ctx.flow_component_id = component_id

end
