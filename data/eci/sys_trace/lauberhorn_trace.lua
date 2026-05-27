local lhtrace = Proto("lhtrace", "Lauberhorn Trace")

local kind_names = {
    [0] = "unknown",
    [1] = "dcs_event",
    [2] = "eci",
    [3] = "lost",
    [4] = "bubble",
    [5] = "lauberhorn_event",
}

local clock_names = {
    [0] = "unknown",
    [1] = "app",
    [2] = "sys",
}

local dcs_names = {
    [0] = "unknown",
    [1] = "even",
    [2] = "odd",
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
f.local_source = ProtoField.uint16("lhtrace.local_source", "Local Source", base.DEC)
f.vc = ProtoField.uint8("lhtrace.vc", "VC", base.DEC)
f.clock_domain = ProtoField.uint8("lhtrace.clock_domain", "Clock Domain", base.DEC, clock_names)
f.dcs = ProtoField.uint8("lhtrace.dcs", "DCS", base.DEC, dcs_names)
f.channel = ProtoField.uint8("lhtrace.channel", "Channel ID", base.DEC)
f.lost_count = ProtoField.uint32("lhtrace.lost_count", "Lost Count", base.DEC)
f.dcs_error = ProtoField.uint8("lhtrace.dcs.error", "DCS Error", base.DEC)
f.dcs_state = ProtoField.uint8("lhtrace.dcs.state", "DCS State", base.DEC)
f.dcs_action = ProtoField.uint8("lhtrace.dcs.action", "DCS Action", base.DEC, action_names)
f.dcs_request = ProtoField.uint8("lhtrace.dcs.request", "DCS Request", base.DEC, request_names)
f.dcs_cli = ProtoField.uint64("lhtrace.dcs.cli", "DCS CLI", base.HEX)
f.eci_header = ProtoField.uint64("lhtrace.eci.header", "ECI Header", base.HEX)
f.eci_opcode = ProtoField.uint8("lhtrace.eci.opcode", "ECI Opcode", base.DEC)
f.stall_count = ProtoField.uint8("lhtrace.eci.stall_count", "Stall Count", base.DEC)
f.stall_shift = ProtoField.uint8("lhtrace.eci.stall_shift", "Stall Shift", base.DEC)
f.event_id = ProtoField.uint16("lhtrace.event.id", "Event ID", base.DEC)
f.event_core = ProtoField.uint16("lhtrace.event.core", "Event Core", base.DEC)
f.payload_len = ProtoField.uint16("lhtrace.payload_len", "Payload Length", base.DEC)
f.payload = ProtoField.bytes("lhtrace.payload", "Payload")

local HEADER_LEN = 72

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
    local source = tvb(32, 2):le_uint()
    local timestamp = tvb(24, 8):le_uint64()
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
    subtree:add_le(f.local_source, tvb(34, 2))
    subtree:add_le(f.vc, tvb(36, 1))
    subtree:add_le(f.clock_domain, tvb(37, 1))
    subtree:add_le(f.dcs, tvb(38, 1))
    subtree:add_le(f.channel, tvb(39, 1))
    subtree:add_le(f.lost_count, tvb(40, 4))
    subtree:add_le(f.dcs_error, tvb(44, 1))
    subtree:add_le(f.dcs_state, tvb(45, 1))
    subtree:add_le(f.dcs_action, tvb(46, 1))
    subtree:add_le(f.dcs_request, tvb(47, 1))
    subtree:add_le(f.dcs_cli, tvb(48, 8))
    subtree:add_le(f.eci_header, tvb(56, 8))

    local opcode = math.floor(tvb(63, 1):uint() / 8)
    subtree:add(f.eci_opcode, tvb(63, 1), opcode)

    subtree:add_le(f.stall_count, tvb(64, 1))
    subtree:add_le(f.stall_shift, tvb(65, 1))
    subtree:add_le(f.event_id, tvb(66, 2))
    subtree:add_le(f.payload_len, tvb(68, 2))
    subtree:add_le(f.event_core, tvb(70, 2))

    local payload_len = tvb(68, 2):le_uint()
    if payload_len > 0 and tvb:len() >= HEADER_LEN + payload_len then
        subtree:add(f.payload, tvb(HEADER_LEN, payload_len))
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
