return function(ctx)
local _ENV = setmetatable(ctx, { __index = _G })

eci_pair_state = {}
eci_frame_pairs = {}
eci_crossing_queues = {}
eci_frame_crossings = {}
eci_crossing_seen_before = {}
eci_delivery_queues = {}
eci_frame_deliveries = {}
eci_frame_credit_updates = {}
credit_return_frame_updates = {}
credit_state_by_link = {}
next_eci_crossing_id = 1
next_eci_delivery_id = 1

local function reset_eci_pairs()
    eci_pair_state = {}
    eci_frame_pairs = {}
    eci_crossing_queues = {}
    eci_frame_crossings = {}
    eci_crossing_seen_before = {}
    eci_delivery_queues = {}
    eci_frame_deliveries = {}
    eci_frame_credit_updates = {}
    credit_return_frame_updates = {}
    credit_state_by_link = {}
    next_eci_crossing_id = 1
    next_eci_delivery_id = 1
end
local function eci_class(source_info)
    local channel = source_info ~= nil and tostring(source_info.channel or "") or ""
    if channel:find("req", 1, true) ~= nil then
        return "mreq"
    elseif channel:find("rsp", 1, true) ~= nil then
        return "mrsp"
    elseif channel:find("fwd", 1, true) ~= nil then
        return "mfwd"
    end
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

local function expected_eci_vc(source_info)
    if source_info == nil then
        return nil
    end
    local channel = tostring(source_info.channel or "")
    local dcs = tostring(source_info.dcs or "")
    local odd = dcs == "odd" or channel:find("odd", 1, true) ~= nil
    local lane = odd and 1 or 0
    if channel == "req_wod_i" then
        return 6 + lane
    elseif channel == "rsp_wod_i" or channel == "rsp_wod_o" then
        return 10 + lane
    elseif channel == "rsp_wd_i" or channel == "rsp_wd_o" then
        return 4 + lane
    elseif channel == "fwd_wod_o" then
        return 8 + lane
    elseif channel == "gsync_req_even" or channel == "gsync_req_odd" then
        return 6 + lane
    elseif channel == "gsync_rsp_even" or channel == "gsync_rsp_odd" then
        return 10 + lane
    end
    return nil
end

local function eci_payload_key(payload_tvb, fields, header, vc)
    local header_field = fields ~= nil and fields.eci_header or nil
    if payload_tvb ~= nil and header_field ~= nil and header_field.offset % 8 == 0 and header_field.width % 8 == 0 then
        local byte_offset = math.floor(header_field.offset / 8)
        local byte_len = math.floor(header_field.width / 8)
        if payload_tvb:len() >= byte_offset + byte_len then
            return payload_tvb(byte_offset, byte_len):bytes():tohex(false, "") .. ":" .. tostring(vc)
        end
    end
    return tostring(header) .. ":" .. tostring(vc)
end

local function eci_frame_record(frame_number)
    local record = eci_frame_pairs[frame_number]
    if record == nil then
        record = {}
        eci_frame_pairs[frame_number] = record
    end
    return record
end

local function add_generated_framenum(tree, field, frame_number, range)
    if frame_number == nil then
        return
    end
    local item = tree:add(field, range, frame_number)
    item.generated = true
end

local function add_generated_value(tree, field, range, value)
    if value == nil then
        return nil
    end
    local item = tree:add(field, range, value)
    item.generated = true
    return item
end

local function is_boundary_source(source_info)
    return source_info ~= nil and tostring(source_info.boundary or "") == "dynamic_static"
end

local function is_outgoing_gateway_source(source_info)
    if source_info == nil or tostring(source_info.type or "") ~= "eci" then
        return false
    end
    if tostring(source_info.clock_domain or "") ~= "sys" or is_boundary_source(source_info) then
        return false
    end
    local channel = tostring(source_info.channel or "")
    return channel:sub(-2) == "_o"
        or channel:sub(1, 10) == "gsync_rsp_"
        or channel == "intc_rsp_vc12"
end

local function delivery_queue(key)
    local queue = eci_delivery_queues[key]
    if queue == nil then
        queue = {}
        eci_delivery_queues[key] = queue
    end
    return queue
end

local function register_delivery_event(record, event)
    local entry = {
        record = record,
        event = event,
    }
    if event.sys_accepted_frame ~= nil then
        eci_frame_deliveries[event.sys_accepted_frame] = entry
    end
    if event.boundary_stalled_frame ~= nil then
        eci_frame_deliveries[event.boundary_stalled_frame] = entry
    end
    if event.boundary_accepted_frame ~= nil then
        eci_frame_deliveries[event.boundary_accepted_frame] = entry
    end
end

local function new_delivery_record(key, source_info, message, header, vc)
    local record = {
        id = next_eci_delivery_id,
        key = key,
        message = message,
        header = header,
        vc = vc,
        sys = nil,
        boundary = nil,
        canonical_frame = nil,
        missing_sys = false,
    }
    next_eci_delivery_id = next_eci_delivery_id + 1
    return record
end

local function note_eci_delivery(source, source_info, frame_number, phase, pair_record, header, vc, payload_key, message)
    if source_info == nil or frame_number == nil then
        return nil
    end

    local key = payload_key
    if is_outgoing_gateway_source(source_info) then
        if phase ~= "accepted" then
            return nil
        end
        local event = {
            stage = "gateway",
            source = source,
            source_label = source_label(source_info, source),
            sys_accepted_frame = frame_number,
        }
        local record = new_delivery_record(key, source_info, message, header, vc)
        record.sys = event
        table.insert(delivery_queue(key), record)
        register_delivery_event(record, event)
        return eci_frame_deliveries[frame_number]
    end

    if not is_boundary_source(source_info) then
        return nil
    end

    local queue = delivery_queue(key)
    local record = nil
    if phase == "accepted" and pair_record ~= nil and pair_record.stalled_frame ~= nil then
        local stalled_entry = eci_frame_deliveries[pair_record.stalled_frame]
        if stalled_entry ~= nil then
            record = stalled_entry.record
        end
    end
    if record == nil and #queue > 0 then
        record = queue[1]
    end
    if record == nil then
        record = new_delivery_record(key, source_info, message, header, vc)
        record.missing_sys = true
    end

    local event = {
        stage = phase == "accepted" and "boundary_accepted" or "boundary_stalled",
        source = source,
        source_label = source_label(source_info, source),
        boundary_stalled_frame = pair_record ~= nil and pair_record.stalled_frame or nil,
        boundary_accepted_frame = phase == "accepted" and frame_number or nil,
    }
    if phase == "valid" then
        event.boundary_stalled_frame = frame_number
    end
    record.boundary = event
    if phase == "accepted" then
        record.canonical_frame = frame_number
        if queue[1] == record then
            table.remove(queue, 1)
        end
    end
    register_delivery_event(record, event)
    return eci_frame_deliveries[frame_number]
end

local function note_eci_pair(source, frame_number, phase, header, vc, payload_key)
    if source == nil or frame_number == nil then
        return nil
    end

    local record = eci_frame_record(frame_number)
    record.phase = phase
    record.source = source
    record.header = header
    record.vc = vc

    if phase == "valid" then
        local previous = eci_pair_state[source]
        if previous ~= nil and previous.accepted_frame == nil then
            previous.unaccepted = true
            eci_frame_record(previous.frame).unaccepted = true
        end
        local pending = {
            frame = frame_number,
            header = header,
            vc = vc,
            key = payload_key,
        }
        eci_pair_state[source] = pending
        record.accepted_frame = nil
        record.unaccepted = true
        return record
    elseif phase == "accepted" then
        local pending = eci_pair_state[source]
        if pending ~= nil then
            local pending_record = eci_frame_record(pending.frame)
            local changed = payload_key ~= pending.key

            pending.accepted_frame = frame_number
            pending.unaccepted = false
            pending_record.accepted_frame = frame_number
            pending_record.unaccepted = false
            pending_record.payload_changed = changed
            record.stalled_frame = pending.frame
            record.payload_changed = changed
            eci_pair_state[source] = nil
        end
        return record
    end

    return record
end

local function eci_crossing_spec(source_info)
    local channel = source_info ~= nil and tostring(source_info.channel or "") or ""
    if channel:sub(-2) == "_i" then
        return {
            direction = "gateway_to_dc",
            before_domain = "sys",
            after_domain = "app",
        }
    elseif channel:sub(-2) == "_o" then
        return {
            direction = "dc_to_gateway",
            before_domain = "app",
            after_domain = "sys",
        }
    end
    return nil
end

local function is_gsync_source(source_info)
    local channel = source_info ~= nil and tostring(source_info.channel or "") or ""
    return channel:sub(1, 6) == "gsync_"
end

local function eci_crossing_stage(source_info, spec)
    local domain = source_info ~= nil and tostring(source_info.clock_domain or "") or ""
    if domain == spec.before_domain then
        return "before"
    elseif domain == spec.after_domain then
        return "after"
    end
    return nil
end

local function eci_crossing_key(source_info, payload_key)
    return table.concat({
        tostring(source_info.dcs or "none"),
        tostring(source_info.channel or "unknown"),
        tostring(payload_key),
    }, ":")
end

local function eci_crossing_summary(record)
    local parts = {
        "ECI",
        tostring(record.direction),
        tostring(record.dcs),
        tostring(record.channel),
        tostring(record.message),
    }
    return table.concat(parts, " ")
end

local function eci_crossing_queue(key)
    local queue = eci_crossing_queues[key]
    if queue == nil then
        queue = {}
        eci_crossing_queues[key] = queue
    end
    return queue
end

local function register_eci_crossing_event(record, event)
    local entry = {
        record = record,
        event = event,
    }
    if event.accepted_frame ~= nil then
        eci_frame_crossings[event.accepted_frame] = entry
    end
    if event.stalled_frame ~= nil then
        eci_frame_crossings[event.stalled_frame] = entry
    end
end

local function new_eci_crossing_record(key, spec, source_info, message, header, vc)
    local record = {
        id = next_eci_crossing_id,
        key = key,
        direction = spec.direction,
        before_domain = spec.before_domain,
        after_domain = spec.after_domain,
        channel = tostring(source_info.channel or "unknown"),
        dcs = tostring(source_info.dcs or "none"),
        message = message,
        header = header,
        vc = vc,
        before = nil,
        after = nil,
        canonical_frame = nil,
        missing_before = false,
        truncated_prefix = false,
    }
    next_eci_crossing_id = next_eci_crossing_id + 1
    return record
end

local function note_eci_crossing(source, source_info, frame_number, phase, pair_record, header, vc, payload_key, message)
    if source_info == nil or frame_number == nil or phase ~= "accepted" then
        return nil
    end

    local spec = eci_crossing_spec(source_info)
    if spec == nil then
        return nil
    end
    local stage = eci_crossing_stage(source_info, spec)
    if stage == nil then
        return nil
    end

    local key = eci_crossing_key(source_info, payload_key)
    local event = {
        stage = stage,
        domain = tostring(source_info.clock_domain or ""),
        source = source,
        source_label = source_label(source_info, source),
        stalled_frame = pair_record ~= nil and pair_record.stalled_frame or nil,
        accepted_frame = frame_number,
    }

    if stage == "before" then
        eci_crossing_seen_before[key] = true
        local record = new_eci_crossing_record(key, spec, source_info, message, header, vc)
        record.before = event
        table.insert(eci_crossing_queue(key), record)
        register_eci_crossing_event(record, event)
        return eci_frame_crossings[frame_number]
    end

    local queue = eci_crossing_queue(key)
    local record = nil
    if #queue > 0 then
        record = table.remove(queue, 1)
    else
        record = new_eci_crossing_record(key, spec, source_info, message, header, vc)
        if eci_crossing_seen_before[key] then
            record.missing_before = true
        else
            record.truncated_prefix = true
        end
    end
    record.after = event
    record.canonical_frame = frame_number
    register_eci_crossing_event(record, event)
    return eci_frame_crossings[frame_number]
end

local function add_eci_pair_fields(tree, record, pinfo, range)
    if record == nil then
        return
    end
    add_generated_framenum(tree, ef.stalled_frame, record.stalled_frame, range)
    add_generated_framenum(tree, ef.accepted_frame, record.accepted_frame, range)
    if record.payload_changed then
        tree:add_proto_expert_info(ee.stall_payload_changed)
    end
    if record.phase == "valid" and record.unaccepted and (pinfo == nil or pinfo.visited) then
        add_generated_value(tree, ef.canonical, range, true)
        tree:add_proto_expert_info(ee.stalled_never_accepted)
    end
end

local function add_crossing_event_frames(tree, range, stalled_field, accepted_field, event)
    if event == nil then
        return
    end
    add_generated_framenum(tree, stalled_field, event.stalled_frame, range)
    add_generated_framenum(tree, accepted_field, event.accepted_frame, range)
end

local function is_canonical_crossing_frame(record, frame_number, pinfo)
    if frame_number == nil then
        return false
    end
    if record.canonical_frame == frame_number then
        return true
    end
    return record.before ~= nil
        and record.after == nil
        and record.before.accepted_frame == frame_number
        and (pinfo == nil or pinfo.visited)
end

local function is_after_accepted_frame(record, frame_number)
    return frame_number ~= nil and record.after ~= nil and record.after.accepted_frame == frame_number
end

local function is_before_accepted_frame(record, frame_number)
    return frame_number ~= nil and record.before ~= nil and record.before.accepted_frame == frame_number
end

local function add_eci_crossing_fields(tree, entry, pinfo, range)
    if entry == nil or entry.record == nil then
        return
    end

    local record = entry.record
    local frame_number = pinfo ~= nil and tonumber(pinfo.number) or nil
    local summary = eci_crossing_summary(record)
    local crossing_tree = add_generated_value(tree, ef.crossing, range, summary)
    if crossing_tree == nil then
        return
    end

    add_generated_value(crossing_tree, ef.crossing_id, range, record.id)
    add_generated_value(crossing_tree, ef.crossing_key, range, record.key)
    add_generated_value(crossing_tree, ef.crossing_direction, range, record.direction)
    if entry.event ~= nil then
        add_generated_value(crossing_tree, ef.crossing_stage, range, entry.event.stage)
    end
    if record.before ~= nil then
        add_generated_value(crossing_tree, ef.crossing_before_source, range, record.before.source_label)
    end
    if record.after ~= nil then
        add_generated_value(crossing_tree, ef.crossing_after_source, range, record.after.source_label)
    end

    add_crossing_event_frames(crossing_tree, range, ef.crossing_before_stalled_frame, ef.crossing_before_accepted_frame, record.before)
    add_crossing_event_frames(crossing_tree, range, ef.crossing_after_stalled_frame, ef.crossing_after_accepted_frame, record.after)

    if is_canonical_crossing_frame(record, frame_number, pinfo) then
        add_generated_value(crossing_tree, ef.canonical, range, true)
        if record.before ~= nil and record.after ~= nil then
            tree:add_proto_expert_info(ee.crossing_matched)
            if record.before.accepted_frame < record.after.accepted_frame then
                tree:add_proto_expert_info(ee.crossing_order_ok)
            else
                tree:add_proto_expert_info(
                    ee.crossing_order_reversed,
                    string.format(
                        "ECI CDC/SLR crossing trace order violates happen-before relationship: before frame %d, after frame %d",
                        record.before.accepted_frame,
                        record.after.accepted_frame
                    )
                )
            end
        end
    end
    if record.missing_before and is_after_accepted_frame(record, frame_number) then
        tree:add_proto_expert_info(ee.crossing_missing_before)
    end
    if record.truncated_prefix and is_after_accepted_frame(record, frame_number) then
        tree:add_proto_expert_info(ee.crossing_missing_before_truncated)
    end
    if record.after == nil and is_before_accepted_frame(record, frame_number) and (pinfo == nil or pinfo.visited) then
        tree:add_proto_expert_info(ee.crossing_missing_after)
    end
end

local function add_eci_delivery_fields(tree, entry, pinfo, range)
    if entry == nil or entry.record == nil then
        return
    end

    local record = entry.record
    local frame_number = pinfo ~= nil and tonumber(pinfo.number) or nil
    local summary = "ECI outgoing " .. tostring(record.message or "unknown")
    local delivery_tree = add_generated_value(tree, ef.delivery, range, summary)
    if delivery_tree == nil then
        return
    end

    add_generated_value(delivery_tree, ef.delivery_id, range, record.id)
    add_generated_value(delivery_tree, ef.delivery_key, range, record.key)
    if entry.event ~= nil then
        add_generated_value(delivery_tree, ef.delivery_stage, range, entry.event.stage)
    end
    if record.sys ~= nil then
        add_generated_value(delivery_tree, ef.delivery_sys_source, range, record.sys.source_label)
        add_generated_framenum(delivery_tree, ef.delivery_sys_accepted_frame, record.sys.sys_accepted_frame, range)
    end
    if record.boundary ~= nil then
        add_generated_value(delivery_tree, ef.delivery_boundary_source, range, record.boundary.source_label)
        add_generated_framenum(delivery_tree, ef.delivery_boundary_stalled_frame, record.boundary.boundary_stalled_frame, range)
        add_generated_framenum(delivery_tree, ef.delivery_boundary_accepted_frame, record.boundary.boundary_accepted_frame, range)
    end

    if frame_number ~= nil and record.canonical_frame == frame_number then
        add_generated_value(delivery_tree, ef.canonical, range, true)
        tree:add_proto_expert_info(ee.delivery_matched)
    end
    if record.missing_sys and record.boundary ~= nil and record.boundary.boundary_accepted_frame == frame_number then
        tree:add_proto_expert_info(ee.crossing_missing_before)
    end
    if record.boundary ~= nil
        and record.boundary.boundary_stalled_frame == frame_number
        and record.boundary.boundary_accepted_frame == nil
        and (pinfo == nil or pinfo.visited) then
        tree:add_proto_expert_info(ee.delivery_stalled_boundary)
    end
    if record.sys ~= nil
        and record.sys.sys_accepted_frame == frame_number
        and record.boundary == nil
        and (pinfo == nil or pinfo.visited) then
        tree:add_proto_expert_info(ee.delivery_missing_boundary)
    end
end

local function credit_modulus(width)
    return 2 ^ width
end

local function credit_signed(raw, width)
    local modulus = credit_modulus(width)
    if raw >= modulus / 2 then
        return raw - modulus
    end
    return raw
end

local function credit_top2(raw, width)
    return math.floor(raw / (2 ^ (width - 2))) % 4
end

local function update_credit_under(old_under, old_raw, new_raw, width)
    local old_top = credit_top2(old_raw, width)
    local new_top = credit_top2(new_raw, width)
    if old_top == 0 and new_top == 3 then
        return true
    elseif old_top == 3 and new_top == 0 then
        return false
    end
    return old_under
end

local function new_credit_state()
    local hi = {}
    for vc = 0, 1 do
        hi[vc] = { raw = 256 - 2, width = 8, under = true }
    end
    for vc = 2, 5 do
        hi[vc] = { raw = 256 - 17, width = 8, under = true }
    end

    local lo = {}
    for vc = 6, 12 do
        lo[vc] = { raw = 32 - 2, width = 5, under = true }
    end

    return {
        hi = hi,
        lo = lo,
        hi_first_cycle = 1,
    }
end

local function link_credit_state(link)
    local key = tostring(link or "unknown")
    local state = credit_state_by_link[key]
    if state == nil then
        state = new_credit_state()
        credit_state_by_link[key] = state
    end
    return state
end

local function credit_counter(state, vc)
    if vc <= 5 then
        return state.hi[vc], "hi"
    end
    return state.lo[vc], "lo"
end

local function apply_credit_delta(counter, delta)
    local before_raw = counter.raw
    local after_raw = (before_raw + delta) % credit_modulus(counter.width)
    local before_under = counter.under
    counter.raw = after_raw
    counter.under = update_credit_under(before_under, before_raw, after_raw, counter.width)
    return {
        before = credit_signed(before_raw, counter.width),
        after = credit_signed(after_raw, counter.width),
        under_before = before_under,
        under_after = counter.under,
    }
end

local function eci_size_words(size)
    if size == 0 then
        return 0
    elseif size == 1 or size == 5 then
        return 1
    elseif size == 2 or size == 6 then
        return 4
    end
    return 8
end

local function eci_size_is_last(size)
    return size == 0 or size == 1 or size == 2 or size == 3
end

local function boundary_credit_decrement(source_info, vc, size)
    local path = tostring(source_info.path or "")
    if path == "hi" then
        if vc <= 5 then
            return eci_size_words(size) + link_credit_state(source_info.link).hi_first_cycle
        end
        return 1
    elseif path == "lo" then
        return 1
    end
    return 0
end

local function note_boundary_credit(source_info, frame_number, phase, vc, size)
    if source_info == nil or frame_number == nil or not is_boundary_source(source_info) then
        return nil
    end
    if eci_frame_credit_updates[frame_number] ~= nil then
        return eci_frame_credit_updates[frame_number]
    end

    local link = tostring(source_info.link or "unknown")
    local path = tostring(source_info.path or "")
    local state = link_credit_state(link)
    local counter, bank = credit_counter(state, vc)
    if counter == nil then
        return nil
    end

    local decrement = boundary_credit_decrement(source_info, vc, size)
    local before_raw = counter.raw
    local before_under = counter.under
    local update = {
        link = link,
        path = path,
        vc = vc,
        bank = bank,
        decrement = phase == "accepted" and decrement or 0,
        before = credit_signed(before_raw, counter.width),
        after = credit_signed(before_raw, counter.width),
        under_before = before_under,
        under_after = before_under,
    }

    if phase == "accepted" and decrement ~= 0 then
        local applied = apply_credit_delta(counter, -decrement)
        update.after = applied.after
        update.under_after = applied.under_after
    end

    if phase == "accepted" and path == "hi" then
        state.hi_first_cycle = eci_size_is_last(size) and 1 or 0
    end

    eci_frame_credit_updates[frame_number] = update
    return update
end

local function add_boundary_credit_fields(tree, update, range)
    if update == nil then
        return
    end
    local credit_tree = add_generated_value(
        tree,
        ef.credit_link,
        range,
        string.format("%s %s VC%d", update.link, update.path, update.vc)
    )
    if credit_tree == nil then
        return
    end
    add_generated_value(credit_tree, ef.credit_path, range, update.path)
    add_generated_value(credit_tree, ef.credit_vc, range, update.vc)
    add_generated_value(credit_tree, ef.credit_decrement, range, update.decrement)
    add_generated_value(credit_tree, ef.credit_before, range, update.before)
    add_generated_value(credit_tree, ef.credit_after, range, update.after)
    add_generated_value(credit_tree, ef.credit_under_before, range, update.under_before)
    add_generated_value(credit_tree, ef.credit_under_after, range, update.under_after)
end

local function apply_credit_return(source_info, frame_number, vector)
    source_info = source_info or {}
    if frame_number ~= nil and credit_return_frame_updates[frame_number] ~= nil then
        return credit_return_frame_updates[frame_number]
    end

    local link = tostring(source_info.link or "unknown")
    local state = link_credit_state(link)
    local updates = {
        link = link,
        vector = vector,
        vcs = {},
    }
    for bit = 0, 10 do
        if math.floor(vector / (2 ^ bit)) % 2 ~= 0 then
            local vc = bit + 2
            local counter, bank = credit_counter(state, vc)
            if counter ~= nil then
                local applied = apply_credit_delta(counter, 8)
                table.insert(updates.vcs, {
                    vc = vc,
                    bank = bank,
                    before = applied.before,
                    after = applied.after,
                    under_before = applied.under_before,
                    under_after = applied.under_after,
                })
            end
        end
    end
    if frame_number ~= nil then
        credit_return_frame_updates[frame_number] = updates
    end
    return updates
end

local function dissect_credit_return(payload_tvb, tree, pinfo, source, source_info)
    source_info = source_info or {}
    local fields = fields_for_type("credit_return") or {}
    local valid, reason = validate_payload_fields(payload_tvb, fields, { "credit_return" }, "credit_return")
    if not valid then
        return malformed_payload_result(tree, payload_tvb, "credit_return", reason)
    end

    local vector = extract_bits_le(payload_tvb, 0, fields.credit_return.offset, fields.credit_return.width)
    local frame_number = pinfo ~= nil and tonumber(pinfo.number) or nil
    local updates
    if pinfo ~= nil and pinfo.visited and frame_number ~= nil then
        updates = credit_return_frame_updates[frame_number]
    else
        updates = apply_credit_return(source_info, frame_number, vector)
    end

    tree:add(cf.return_vector, byte_range_for_bits(payload_tvb, fields.credit_return.offset, fields.credit_return.width), vector)
    tree:add(cf.link, anchor_range(payload_tvb), tostring(source_info.link or "unknown"))
    local parts = {}
    if updates ~= nil then
        for _, update in ipairs(updates.vcs) do
            local text = string.format("VC%d %d->%d", update.vc, update.before, update.after)
            table.insert(parts, text)
            local item = tree:add(cf.update, anchor_range(payload_tvb), text)
            item.generated = true
            item:add(cf.vc, anchor_range(payload_tvb), update.vc).generated = true
            item:add(cf.before, anchor_range(payload_tvb), update.before).generated = true
            item:add(cf.after, anchor_range(payload_tvb), update.after).generated = true
            item:add(cf.under_before, anchor_range(payload_tvb), update.under_before).generated = true
            item:add(cf.under_after, anchor_range(payload_tvb), update.under_after).generated = true
        end
    end

    local info = "credit_return " .. tostring(source_info.link or "unknown") .. string.format(" vector=0x%03x", vector)
    if #parts > 0 then
        info = info .. " " .. table.concat(parts, " ")
    end
    return {
        info = info,
        dest = tostring(source_info.link or ""),
    }
end
local function eci_gsync_details(payload_tvb, tree, class, opcode)
    if opcode ~= 24 or (class ~= "mreq" and class ~= "mrsp") then
        return nil
    end

    local values = {
        rtad = extract_bits_le(payload_tvb, 0, 7, 3),
        ppvid = extract_bits_le(payload_tvb, 0, 0, 6),
    }
    tree:add(ef.rtad, byte_range_for_bits(payload_tvb, 7, 3), values.rtad)
    tree:add(ef.ppvid, byte_range_for_bits(payload_tvb, 0, 6), values.ppvid)

    if class == "mreq" then
        values.rreq_id = extract_bits_le(payload_tvb, 0, 50, 5)
        tree:add(ef.rreq_id, byte_range_for_bits(payload_tvb, 50, 5), values.rreq_id)
        return {
            info = string.format("rreq_id=%d rtad=%d ppvid=%d", values.rreq_id, values.rtad, values.ppvid),
            dest = string.format("rtad=%d ppvid=%d", values.rtad, values.ppvid),
        }
    end

    values.ns = extract_bits_le(payload_tvb, 0, 45, 1)
    tree:add(ef.ns, byte_range_for_bits(payload_tvb, 45, 1), values.ns)
    return {
        info = string.format("ns=%d rtad=%d ppvid=%d", values.ns, values.rtad, values.ppvid),
        dest = string.format("rtad=%d ppvid=%d", values.rtad, values.ppvid),
    }
end
local function is_intc_source(source_info)
    local channel = source_info ~= nil and tostring(source_info.channel or "") or ""
    return channel == "intc_req_vc12" or channel == "intc_rsp_vc12"
end
local function dissect_eci_intc(payload_tvb, tree, source_info, header, vc, accepted, phase)
    local channel = tostring(source_info.channel or "")
    local low16 = extract_bits_le(payload_tvb, 0, 0, 16)
    local rsp_word1_prefix = extract_bits_le(payload_tvb, 0, 16, 48)
    local word = 0
    if channel == "intc_rsp_vc12" and rsp_word1_prefix == 0x000000000080 then
        word = 1
    elseif channel == "intc_req_vc12" and low16 == 0 and header ~= 0 then
        word = 1
    end
    local prefix = channel == "intc_req_vc12" and "INTC REQ" or "INTC RSP"
    local kind = nil
    local details = {}

    tree:add(ef.vc, byte_range_for_bits(payload_tvb, 64, 4), vc)
    tree:add(ef.accepted, byte_range_for_bits(payload_tvb, 68, 1), accepted)
    tree:add(ef.phase, byte_range_for_bits(payload_tvb, 68, 1), phase)
    tree:add_le(ef.header, byte_range_for_bits(payload_tvb, 0, 64))
    tree:add(ef.intc_word, byte_range_for_bits(payload_tvb, 0, 16), word)

    if channel == "intc_req_vc12" then
        local cmd = extract_bits_le(payload_tvb, 0, 0, 8)
        if word == 0 and cmd == 0x17 then
            kind = "cpu_sgi"
            local intid = extract_bits_le(payload_tvb, 0, 44, 4)
            local affinity1 = extract_bits_le(payload_tvb, 0, 48, 4)
            tree:add(ef.intc_cmd, byte_range_for_bits(payload_tvb, 0, 8), cmd)
            tree:add(ef.intc_intid, byte_range_for_bits(payload_tvb, 44, 4), intid)
            tree:add(ef.intc_affinity1, byte_range_for_bits(payload_tvb, 48, 4), affinity1)
            table.insert(details, string.format("cmd=0x%02x", cmd))
            table.insert(details, string.format("intid=%d", intid))
            table.insert(details, string.format("aff1=%d", affinity1))
        elseif word == 1 then
            kind = "cpu_sgi_affinity"
            local affinity0 = extract_bits_le(payload_tvb, 0, 0, 16)
            tree:add(ef.intc_affinity0, byte_range_for_bits(payload_tvb, 0, 16), affinity0)
            table.insert(details, string.format("aff0=0x%04x", affinity0))
        else
            kind = "vc12_req_raw"
        end
    else
        local rsp_word0_payload = extract_bits_le(payload_tvb, 0, 0, 44)
        if word == 0 and rsp_word0_payload == 0x0500080c817 then
            kind = "fpga_sgi"
            local intid = extract_bits_le(payload_tvb, 0, 44, 4)
            local affinity1 = extract_bits_le(payload_tvb, 0, 48, 4)
            tree:add(ef.intc_cmd, byte_range_for_bits(payload_tvb, 0, 8), 0)
            tree:add(ef.intc_intid, byte_range_for_bits(payload_tvb, 44, 4), intid)
            tree:add(ef.intc_affinity1, byte_range_for_bits(payload_tvb, 48, 4), affinity1)
            table.insert(details, "cmd=0x00")
            table.insert(details, string.format("intid=%d", intid))
            table.insert(details, string.format("aff1=%d", affinity1))
        elseif word == 1 and rsp_word1_prefix == 0x000000000080 then
            kind = "fpga_sgi_affinity"
            local affinity0 = extract_bits_le(payload_tvb, 0, 0, 16)
            tree:add(ef.intc_affinity0, byte_range_for_bits(payload_tvb, 0, 16), affinity0)
            table.insert(details, string.format("aff0=0x%04x", affinity0))
        else
            kind = "vc12_rsp_raw"
        end
    end

    tree:add(ef.intc_kind, anchor_range(payload_tvb), kind)
    local message = prefix .. " word" .. tostring(word) .. " " .. kind
    if #details > 0 then
        message = message .. " " .. table.concat(details, " ")
    end
    local item = tree:add(ef.message, anchor_range(payload_tvb), message)
    item.generated = true
    return {
        info = message,
        dest = "VC12 interrupt",
    }
end
local function dissect_eci(payload_tvb, tree, pinfo, source, source_info)
    local fields = fields_for_type("eci") or {}
    if payload_tvb:len() < 8 then
        return malformed_payload_result(tree, payload_tvb, "ECI", "payload is shorter than the 64-bit ECI header")
    end
    local valid, reason = validate_payload_fields(payload_tvb, fields, { "eci_header", "vc", "accepted" }, "eci")
    if not valid then
        return malformed_payload_result(tree, payload_tvb, "ECI", reason)
    end

    local header = extract_bits_le(payload_tvb, 0, fields.eci_header.offset, fields.eci_header.width)
    local vc = extract_bits_le(payload_tvb, 0, fields.vc.offset, fields.vc.width)
    local accepted = extract_bits_le(payload_tvb, 0, fields.accepted.offset, fields.accepted.width)
    local size = 0
    if fields.size ~= nil and tvb_has_bits(payload_tvb, tonumber(fields.size.offset), tonumber(fields.size.width)) then
        size = extract_bits_le(payload_tvb, 0, fields.size.offset, fields.size.width)
    end
    local phase = accepted ~= 0 and "accepted" or "valid"
    if is_intc_source(source_info) then
        return dissect_eci_intc(payload_tvb, tree, source_info, header, vc, accepted, phase)
    end

    local opcode = math.floor(payload_tvb(7, 1):uint() / 8)
    local class = eci_class(source_info)
    local opcode_item = tree:add(ef.opcode, payload_tvb(7, 1), opcode)
    local message = eci_opcode_name(source_info, opcode)
    if message ~= nil then
        opcode_item:append_text(" (" .. message .. ")")
        local item = tree:add(ef.message, anchor_range(payload_tvb), message)
        item.generated = true
    end
    local frame_number = pinfo ~= nil and tonumber(pinfo.number) or nil
    local pair_key = eci_payload_key(payload_tvb, fields, header, vc)
    local pair_record = nil
    local crossing_entry = nil
    local delivery_entry = nil
    local credit_update = nil
    if pinfo ~= nil and pinfo.visited and frame_number ~= nil then
        pair_record = eci_frame_pairs[frame_number]
        crossing_entry = eci_frame_crossings[frame_number]
        delivery_entry = eci_frame_deliveries[frame_number]
        credit_update = eci_frame_credit_updates[frame_number]
    else
        pair_record = note_eci_pair(source, frame_number, phase, header, vc, pair_key)
        crossing_entry = note_eci_crossing(
            source,
            source_info,
            frame_number,
            phase,
            pair_record,
            header,
            vc,
            pair_key,
            message or string.format("opcode_%d", opcode)
        )
        delivery_entry = note_eci_delivery(
            source,
            source_info,
            frame_number,
            phase,
            pair_record,
            header,
            vc,
            pair_key,
            message or string.format("opcode_%d", opcode)
        )
        credit_update = note_boundary_credit(source_info, frame_number, phase, vc, size)
    end
    local gsync_details = eci_gsync_details(payload_tvb, tree, class, opcode)
    local aliased_addr
    if gsync_details ~= nil then
        aliased_addr = nil
    elseif class == "mrsp" and (opcode == 9 or opcode == 10) then
        aliased_addr = extract_bits_le(payload_tvb, 0, 7, 33) * 128
    else
        aliased_addr = extract_bits_le(payload_tvb, 0, 0, 40)
    end
    local dest
    if gsync_details ~= nil then
        dest = gsync_details.dest
    else
        local unaliased = unalias_address(aliased_addr)
        dest = fmt_addr(unaliased)
        tree:add(ef.unaliased_addr, byte_range_for_bits(payload_tvb, 0, 40), dest)
    end
    tree:add(ef.vc, byte_range_for_bits(payload_tvb, fields.vc.offset, fields.vc.width), vc)
    tree:add(ef.accepted, byte_range_for_bits(payload_tvb, fields.accepted.offset, fields.accepted.width), accepted)
    tree:add(ef.phase, byte_range_for_bits(payload_tvb, fields.accepted.offset, fields.accepted.width), phase)
    if fields.size ~= nil then
        tree:add(ef.size, byte_range_for_bits(payload_tvb, fields.size.offset, fields.size.width), size)
    end
    tree:add_le(ef.header, byte_range_for_bits(payload_tvb, fields.eci_header.offset, fields.eci_header.width))
    if vc == 0 then
        tree:add_proto_expert_info(ee.vc_zero)
    end
    if header == 0 then
        tree:add_proto_expert_info(ee.header_zero)
    end
    local expected_vc = expected_eci_vc(source_info)
    if expected_vc ~= nil and vc ~= expected_vc then
        tree:add_proto_expert_info(
            ee.wrong_vc,
            string.format("ECI opcode appeared on VC%d, expected VC%d for this source", vc, expected_vc)
        )
    end
    add_eci_pair_fields(tree, pair_record, pinfo, anchor_range(payload_tvb))
    add_eci_crossing_fields(tree, crossing_entry, pinfo, anchor_range(payload_tvb))
    add_eci_delivery_fields(tree, delivery_entry, pinfo, anchor_range(payload_tvb))
    add_boundary_credit_fields(tree, credit_update, anchor_range(payload_tvb))
    if is_gsync_source(source_info) then
        add_generated_value(tree, ef.canonical, anchor_range(payload_tvb), true)
    end

    local info = (message or string.format("opcode_%d", opcode))
    if gsync_details ~= nil then
        info = info .. " " .. gsync_details.info
    end

    return {
        info = info .. " " .. phase,
        dest = dest,
    }
end

ctx.reset_eci_pairs = reset_eci_pairs
ctx.eci_class = eci_class
ctx.eci_opcode_name = eci_opcode_name
ctx.expected_eci_vc = expected_eci_vc
ctx.dissect_eci = dissect_eci
ctx.dissect_credit_return = dissect_credit_return

end
