local lhtrace = Proto("lhtrace", "Lauberhorn Trace")
local lhmeta = Proto("lhtrace_meta", "Lauberhorn Trace Metadata")
local lhmarker = Proto("lhtrace_marker", "Lauberhorn Trace Marker")
local lhdcs = Proto("lhtrace_dcs", "Lauberhorn DCS Trace")
local lheci = Proto("lhtrace_eci", "Lauberhorn ECI Trace")
local lhevent = Proto("lhtrace_event", "Lauberhorn Event Trace")
local lhraw = Proto("lhtrace_raw", "Lauberhorn Raw Trace")

local KIND_METADATA = 1
local KIND_SAMPLE = 2
local KIND_LOST = 3
local KIND_BUBBLE = 4

local kind_names = {
    [1] = "metadata",
    [2] = "sample",
    [3] = "lost",
    [4] = "bubble",
}

local request_names = {
    [0] = "UNKNOWN_REQ", [1] = "A11", [2] = "A21", [3] = "A22",
    [4] = "A31", [5] = "A31d", [6] = "A32", [7] = "A32d",
    [8] = "F21", [9] = "F31", [10] = "F32", [11] = "ICI",
    [12] = "LC", [13] = "LCI", [14] = "LR", [15] = "LW",
    [16] = "R12", [17] = "R13", [18] = "R23", [19] = "RDDA",
    [20] = "RR", [21] = "RW", [22] = "UL", [23] = "V21",
    [24] = "V31", [25] = "V31d", [26] = "V32", [27] = "V32d",
    [28] = "WDDA",
}

local action_names = {
    [0] = "NO_ACTION", [1] = "NOT_ALLOWED", [2] = "RDD",
    [3] = "SEND_F21", [4] = "SEND_F31", [5] = "SEND_F32",
    [6] = "SEND_LCA", [7] = "SEND_LCIA", [8] = "SEND_LRA",
    [9] = "SEND_LWA", [10] = "SEND_RA2", [11] = "SEND_RA3",
    [12] = "SEND_RRA", [13] = "SEND_RWA", [14] = "STALL",
    [15] = "WDD",
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

local mf = lhmeta.fields
mf.json = ProtoField.string("lhtrace.meta.json", "Trace Map JSON")

local sf = lhraw.fields
sf.source_type = ProtoField.string("lhtrace.source.type", "Source Type")
sf.source_port = ProtoField.string("lhtrace.source.port", "Source Port")
sf.clock_domain = ProtoField.string("lhtrace.source.clock_domain", "Clock Domain")
sf.dcs = ProtoField.string("lhtrace.source.dcs", "DCS")
sf.channel = ProtoField.string("lhtrace.source.channel", "Channel")
sf.local_source = ProtoField.uint16("lhtrace.source.local", "Local Source", base.DEC)

local df = lhdcs.fields
df.error = ProtoField.uint8("lhtrace.dcs.error", "Error", base.DEC)
df.cli = ProtoField.uint64("lhtrace.dcs.cli", "CLI", base.HEX)
df.state = ProtoField.uint8("lhtrace.dcs.state", "State", base.DEC)
df.action = ProtoField.uint8("lhtrace.dcs.action", "Action", base.DEC, action_names)
df.request = ProtoField.uint8("lhtrace.dcs.request", "Request", base.DEC, request_names)

local ef = lheci.fields
ef.header = ProtoField.uint64("lhtrace.eci.header", "Header", base.HEX)
ef.opcode = ProtoField.uint8("lhtrace.eci.opcode", "Opcode", base.DEC)
ef.vc = ProtoField.uint8("lhtrace.eci.vc", "VC", base.DEC)
ef.stall_count = ProtoField.uint8("lhtrace.eci.stall_count", "Stall Count", base.DEC)
ef.stall_cycles = ProtoField.uint32("lhtrace.eci.stall_cycles", "Stall Cycles", base.DEC)
ef.accepted = ProtoField.uint8("lhtrace.eci.accepted", "Accepted", base.DEC)

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

local function add_source_info(subtree, tvb, source)
    local src = sources_by_id[source]
    if src == nil then
        return { type = "unknown" }
    end
    subtree:add(sf.source_type, tvb(0, 0), src.type or "")
    subtree:add(sf.source_port, tvb(0, 0), src.port or "")
    subtree:add(sf.clock_domain, tvb(0, 0), src.clock_domain or "")
    subtree:add(sf.dcs, tvb(0, 0), src.dcs or "")
    subtree:add(sf.channel, tvb(0, 0), src.channel or "")
    if src.local_source ~= nil then
        subtree:add(sf.local_source, tvb(0, 0), tonumber(src.local_source))
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

local function dissect_dcs(payload_tvb, tree)
    local fields = fields_for_type("dcs_event") or {}
    local subtree = tree:add(lhdcs, payload_tvb(), "DCS Trace")
    subtree:add(df.error, payload_tvb(0, 0), extract_bits_le(payload_tvb, 0, fields.error.offset, fields.error.width))
    subtree:add(df.cli, payload_tvb(0, 0), extract_bits_le(payload_tvb, 0, fields.cli.offset, fields.cli.width))
    subtree:add(df.state, payload_tvb(0, 0), extract_bits_le(payload_tvb, 0, fields.state.offset, fields.state.width))
    subtree:add(df.action, payload_tvb(0, 0), extract_bits_le(payload_tvb, 0, fields.action.offset, fields.action.width))
    subtree:add(df.request, payload_tvb(0, 0), extract_bits_le(payload_tvb, 0, fields.request.offset, fields.request.width))
end

local function dissect_eci(payload_tvb, tree)
    local fields = fields_for_type("eci") or {}
    local subtree = tree:add(lheci, payload_tvb(), "ECI Trace")
    subtree:add_le(ef.header, payload_tvb(0, 8))
    local opcode = math.floor(payload_tvb(7, 1):uint() / 8)
    subtree:add(ef.opcode, payload_tvb(7, 1), opcode)
    local vc = extract_bits_le(payload_tvb, 0, fields.vc.offset, fields.vc.width)
    local stall_count = extract_bits_le(payload_tvb, 0, fields.stall_count.offset, fields.stall_count.width)
    local accepted = extract_bits_le(payload_tvb, 0, fields.accepted.offset, fields.accepted.width)
    local shift = 0
    if trace_map ~= nil and trace_map.sample ~= nil then
        shift = tonumber(trace_map.sample.eci_stall_counter_shift) or 0
    end
    subtree:add(ef.vc, payload_tvb(0, 0), vc)
    subtree:add(ef.stall_count, payload_tvb(0, 0), stall_count)
    subtree:add(ef.stall_cycles, payload_tvb(0, 0), stall_count * (2 ^ shift))
    subtree:add(ef.accepted, payload_tvb(0, 0), accepted)
end

local function dissect_event(payload_tvb, tree)
    local fields = fields_for_type("lauberhorn_event") or {}
    local id_field = fields.event_id or { offset = 0, width = 6 }
    local extra_field = fields.extra_data or { offset = 6, width = 69 }
    local core_field = fields.core_id or { offset = 6, width = 6 }
    local event_id = extract_bits_le(payload_tvb, 0, id_field.offset, id_field.width)
    local name = event_name(event_id)
    local subtree = tree:add(lhevent, payload_tvb(), "Lauberhorn Event: " .. name)
    subtree:add(evf.event_id, payload_tvb(0, 0), event_id):append_text(" (" .. name .. ")")
    subtree:add(evf.event_name, payload_tvb(0, 0), name)
    local extra_byte_offset = math.floor(extra_field.offset / 8)
    local extra_byte_len = math.ceil(((extra_field.offset % 8) + extra_field.width) / 8)
    subtree:add(evf.extra_data, payload_tvb(extra_byte_offset, extra_byte_len))
    subtree:add(evf.core_id, payload_tvb(0, 0), extract_bits_le(payload_tvb, 0, core_field.offset, core_field.width))
end

local function dissect_sample_payload(payload_tvb, source, tree)
    local src = add_source_info(tree, payload_tvb, source)
    if src.type == "dcs_event" then
        dissect_dcs(payload_tvb, tree)
    elseif src.type == "eci" then
        dissect_eci(payload_tvb, tree)
    elseif src.type == "lauberhorn_event" then
        dissect_event(payload_tvb, tree)
    else
        tree:add(f.payload, payload_tvb())
    end
end

function lhtrace.dissector(tvb, pinfo, tree)
    if tvb:len() < HEADER_LEN then
        return 0
    end
    if tvb(0, 4):string() ~= "LHTR" then
        return 0
    end

    pinfo.cols.protocol = "LHTRACE"

    local kind = tvb(5, 1):uint()
    local sample = tvb(8, 8):le_uint64()
    local physical_sample = tvb(16, 8):le_uint64()
    local timestamp = tvb(24, 8):le_uint64()
    local source = tvb(32, 2):le_uint()
    local lost_count = tvb(36, 4):le_uint()
    local payload_len = tvb(40, 4):le_uint()

    local info = string.format("%s src=%u sample=%s ts=%s",
        kind_names[kind] or "unknown",
        source,
        tostring(sample),
        tostring(timestamp))
    pinfo.cols.info = info

    local subtree = tree:add(lhtrace, tvb(), "Lauberhorn Trace: " .. info)
    subtree:add(f.magic, tvb(0, 4))
    subtree:add_le(f.version, tvb(4, 1))
    subtree:add_le(f.kind, tvb(5, 1))
    subtree:add_le(f.flags, tvb(6, 2))
    subtree:add_le(f.sample, tvb(8, 8))
    subtree:add_le(f.physical_sample, tvb(16, 8))
    subtree:add_le(f.timestamp, tvb(24, 8))
    subtree:add_le(f.source, tvb(32, 2))
    subtree:add_le(f.lost_count, tvb(36, 4))
    subtree:add_le(f.payload_len, tvb(40, 4))

    if payload_len > 0 and tvb:len() >= HEADER_LEN + payload_len then
        local payload_tvb = tvb(HEADER_LEN, payload_len)
        if kind == KIND_METADATA then
            local json_text = payload_tvb:string()
            load_trace_map(json_text)
            local meta_tree = subtree:add(lhmeta, payload_tvb(), "Trace Map Metadata")
            meta_tree:add(mf.json, payload_tvb())
        elseif kind == KIND_SAMPLE then
            dissect_sample_payload(payload_tvb, source, subtree)
        else
            subtree:add(f.payload, payload_tvb)
        end
    elseif kind == KIND_LOST or kind == KIND_BUBBLE then
        subtree:add(lhmarker, tvb(), (kind_names[kind] or "marker") .. " count=" .. tostring(lost_count))
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
