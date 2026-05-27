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
f.timestamp = ProtoField.uint64("lhtrace.timestamp", "Timestamp", base.DEC)
f.source = ProtoField.uint16("lhtrace.source", "Source", base.DEC)
f.lost_count = ProtoField.uint32("lhtrace.lost_count", "Lost Count", base.DEC)
f.payload_len = ProtoField.uint32("lhtrace.payload_len", "Payload Length", base.DEC)
f.payload = ProtoField.bytes("lhtrace.payload", "Payload")
f.source_name = ProtoField.string("lhtrace.source.name", "Source")

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
ef.stall_count = ProtoField.uint8("lhtrace.eci.stall_count", "Stall Count", base.DEC)
ef.stall_cycles = ProtoField.uint32("lhtrace.eci.stall_cycles", "Stall Cycles", base.DEC)
ef.accepted = ProtoField.uint8("lhtrace.eci.accepted", "Accepted", base.DEC)
ef.message = ProtoField.string("lhtrace.eci.message", "Message")
ef.unaliased_addr = ProtoField.string("lhtrace.eci.unaliased_addr", "Unaliased Address")

local evf = lhevent.fields
evf.event_id = ProtoField.uint16("lhtrace.event.id", "Event ID", base.DEC)
evf.event_name = ProtoField.string("lhtrace.event.name", "Event Name")
evf.extra_data = ProtoField.bytes("lhtrace.event.extra_data", "Extra Data")
evf.core_id = ProtoField.uint16("lhtrace.event.core", "Core ID", base.DEC)

local HEADER_LEN = 44
local trace_map = nil
local sources_by_id = {}

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

local function load_trace_map(json_text)
    trace_map = json_decode(json_text)
    sources_by_id = {}
    if trace_map ~= nil and trace_map.sources ~= nil then
        for _, src in ipairs(trace_map.sources) do
            sources_by_id[tonumber(src.source)] = src
        end
    end
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

local function event_name_from_payload(payload_tvb)
    local fields = fields_for_type("lauberhorn_event") or {}
    local id_field = fields.event_id or { offset = 0, width = 6 }
    return event_name(extract_bits_le(payload_tvb, 0, id_field.offset, id_field.width))
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
    local stall_count = extract_bits_le(payload_tvb, 0, fields.stall_count.offset, fields.stall_count.width)
    local accepted = extract_bits_le(payload_tvb, 0, fields.accepted.offset, fields.accepted.width)
    local aliased_addr
    if eci_class(source_info) == "mrsp" and (opcode == 9 or opcode == 10) then
        aliased_addr = extract_bits_le(payload_tvb, 0, 7, 33) * 128
    else
        aliased_addr = extract_bits_le(payload_tvb, 0, 0, 40)
    end
    local unaliased = unalias_address(aliased_addr)
    local shift = 0
    if trace_map ~= nil and trace_map.sample ~= nil then
        shift = tonumber(trace_map.sample.eci_stall_counter_shift) or 0
    end
    tree:add(ef.unaliased_addr, byte_range_for_bits(payload_tvb, 0, 40), fmt_addr(unaliased))
    tree:add(ef.vc, byte_range_for_bits(payload_tvb, fields.vc.offset, fields.vc.width), vc)
    tree:add(ef.accepted, byte_range_for_bits(payload_tvb, fields.accepted.offset, fields.accepted.width), accepted)
    tree:add(ef.stall_cycles, byte_range_for_bits(payload_tvb, fields.stall_count.offset, fields.stall_count.width), stall_count * (2 ^ shift))
    tree:add(ef.stall_count, byte_range_for_bits(payload_tvb, fields.stall_count.offset, fields.stall_count.width), stall_count)
    tree:add_le(ef.header, byte_range_for_bits(payload_tvb, fields.eci_header.offset, fields.eci_header.width))

    return {
        info = message or string.format("opcode_%d", opcode),
        dest = fmt_addr(unaliased),
    }
end

local function dissect_event(payload_tvb, tree)
    local fields = fields_for_type("lauberhorn_event") or {}
    local id_field = fields.event_id or { offset = 0, width = 6 }
    local extra_field = fields.extra_data or { offset = 6, width = 69 }
    local core_field = fields.core_id or { offset = 6, width = 6 }
    local event_id = extract_bits_le(payload_tvb, 0, id_field.offset, id_field.width)
    local name = event_name(event_id)
    tree:add(evf.event_name, byte_range_for_bits(payload_tvb, id_field.offset, id_field.width), name)
    tree:add(evf.event_id, byte_range_for_bits(payload_tvb, id_field.offset, id_field.width), event_id):append_text(" (" .. name .. ")")
    local extra_byte_offset = math.floor(extra_field.offset / 8)
    local extra_byte_len = math.ceil(((extra_field.offset % 8) + extra_field.width) / 8)
    tree:add(evf.extra_data, payload_tvb(extra_byte_offset, extra_byte_len))
    tree:add(evf.core_id, byte_range_for_bits(payload_tvb, core_field.offset, core_field.width), extract_bits_le(payload_tvb, 0, core_field.offset, core_field.width))
    return {
        info = name,
        source = name,
        dest = "",
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
    local source = tvb(32, 2):le_uint()
    local lost_count = tvb(36, 4):le_uint()
    local payload_len = tvb(40, 4):le_uint()
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
            local parsed = dissect_event(payload_tvb, subtree)
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
    raw_tree:add_le(f.source, tvb(32, 2))
    raw_tree:add_le(f.lost_count, tvb(36, 4))
    raw_tree:add_le(f.payload_len, tvb(40, 4))
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
