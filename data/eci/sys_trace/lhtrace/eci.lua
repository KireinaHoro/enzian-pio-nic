return function(ctx)
local _ENV = setmetatable(ctx, { __index = _G })

eci_pair_state = {}
eci_frame_pairs = {}
eci_crossing_queues = {}
eci_frame_crossings = {}
eci_crossing_seen_before = {}
next_eci_crossing_id = 1

local function reset_eci_pairs()
    eci_pair_state = {}
    eci_frame_pairs = {}
    eci_crossing_queues = {}
    eci_frame_crossings = {}
    eci_crossing_seen_before = {}
    next_eci_crossing_id = 1
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
            direction = "dc_to_eci_gateway",
            before_domain = "sys",
            after_domain = "app",
        }
    elseif channel:sub(-2) == "_o" then
        return {
            direction = "eci_gateway_to_dc",
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
        tree:add_proto_expert_info(ee.stall_payload_changed, "ECI payload changed while stalled")
    end
    if record.phase == "valid" and record.unaccepted and (pinfo == nil or pinfo.visited) then
        tree:add_proto_expert_info(ee.stalled_never_accepted, "ECI stalled frame was never accepted")
    end
end

local function add_crossing_event_frames(tree, range, stalled_field, accepted_field, event)
    if event == nil then
        return
    end
    add_generated_framenum(tree, stalled_field, event.stalled_frame, range)
    add_generated_framenum(tree, accepted_field, event.accepted_frame, range)
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

    if frame_number ~= nil and record.canonical_frame == frame_number then
        add_generated_value(crossing_tree, ef.canonical, range, true)
        if record.before ~= nil and record.after ~= nil then
            tree:add_proto_expert_info(ee.crossing_matched, "ECI CDC/SLR crossing matched before and after sides")
            if record.before.accepted_frame < record.after.accepted_frame then
                tree:add_proto_expert_info(
                    ee.crossing_order_ok,
                    "ECI CDC/SLR crossing trace order matches happen-before relationship"
                )
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
    if record.missing_before and entry.event ~= nil and entry.event.stage == "after" then
        tree:add_proto_expert_info(ee.crossing_missing_before, "ECI CDC/SLR crossing had no matching before-side frame")
    end
    if record.before ~= nil and record.after == nil and (pinfo == nil or pinfo.visited) then
        tree:add_proto_expert_info(ee.crossing_missing_after, "ECI CDC/SLR crossing had no matching after-side frame")
    end
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
local function dissect_eci(payload_tvb, tree, pinfo, source, source_info)
    local fields = fields_for_type("eci") or {}
    if payload_tvb:len() < 8 then
        return malformed_payload_result(tree, payload_tvb, "ECI", "payload is shorter than the 64-bit ECI header")
    end
    local valid, reason = validate_payload_fields(payload_tvb, fields, { "eci_header", "vc", "accepted" }, "eci")
    if not valid then
        return malformed_payload_result(tree, payload_tvb, "ECI", reason)
    end

    local opcode = math.floor(payload_tvb(7, 1):uint() / 8)
    local class = eci_class(source_info)
    local header = extract_bits_le(payload_tvb, 0, fields.eci_header.offset, fields.eci_header.width)
    local opcode_item = tree:add(ef.opcode, payload_tvb(7, 1), opcode)
    local message = eci_opcode_name(source_info, opcode)
    if message ~= nil then
        opcode_item:append_text(" (" .. message .. ")")
        local item = tree:add(ef.message, anchor_range(payload_tvb), message)
        item.generated = true
    end
    local vc = extract_bits_le(payload_tvb, 0, fields.vc.offset, fields.vc.width)
    local accepted = extract_bits_le(payload_tvb, 0, fields.accepted.offset, fields.accepted.width)
    local phase = accepted ~= 0 and "accepted" or "valid"
    local frame_number = pinfo ~= nil and tonumber(pinfo.number) or nil
    local pair_key = eci_payload_key(payload_tvb, fields, header, vc)
    local pair_record = nil
    local crossing_entry = nil
    if pinfo ~= nil and pinfo.visited and frame_number ~= nil then
        pair_record = eci_frame_pairs[frame_number]
        crossing_entry = eci_frame_crossings[frame_number]
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
    tree:add_le(ef.header, byte_range_for_bits(payload_tvb, fields.eci_header.offset, fields.eci_header.width))
    if vc == 0 then
        tree:add_proto_expert_info(ee.vc_zero, "ECI frame decoded with VC0")
    end
    if header == 0 then
        tree:add_proto_expert_info(ee.header_zero, "ECI frame decoded with zero header")
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

end
