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

local de = {}
de.cli_low_bits_nonzero = ProtoExpert.new(
    "lhtrace.dcs.cli_low_bits_nonzero",
    "DCS CLI lower address bits are nonzero",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
lhdcs.experts = { de.cli_low_bits_nonzero }

local ef = lheci_app.fields
ef.header = ProtoField.uint64("lhtrace.eci.header", "Header", base.HEX)
ef.opcode = ProtoField.uint8("lhtrace.eci.opcode", "Opcode", base.DEC)
ef.vc = ProtoField.uint8("lhtrace.eci.vc", "VC", base.DEC)
ef.accepted = ProtoField.uint8("lhtrace.eci.accepted", "Accepted", base.DEC)
ef.phase = ProtoField.string("lhtrace.eci.phase", "Phase")
ef.message = ProtoField.string("lhtrace.eci.message", "Message")
ef.unaliased_addr = ProtoField.string("lhtrace.eci.unaliased_addr", "Unaliased Address")
ef.rreq_id = ProtoField.uint8("lhtrace.eci.rreq_id", "RReqID", base.DEC)
ef.ns = ProtoField.uint8("lhtrace.eci.ns", "NS", base.DEC)
ef.rtad = ProtoField.uint8("lhtrace.eci.rtad", "RTAD", base.DEC)
ef.ppvid = ProtoField.uint8("lhtrace.eci.ppvid", "PPVID", base.DEC)
ef.stalled_frame = ProtoField.framenum("lhtrace.eci.stalled_frame", "Stalled Frame", base.NONE, frametype.REQUEST)
ef.accepted_frame = ProtoField.framenum("lhtrace.eci.accepted_frame", "Accepted Frame", base.NONE, frametype.RESPONSE)
ef.canonical = ProtoField.bool("lhtrace.eci.canonical", "Canonical ECI Frame")
ef.crossing = ProtoField.string("lhtrace.eci.crossing", "ECI CDC/SLR Crossing")
ef.crossing_id = ProtoField.uint32("lhtrace.eci.crossing.id", "ECI Crossing ID", base.DEC)
ef.crossing_key = ProtoField.string("lhtrace.eci.crossing.key", "ECI Crossing Key")
ef.crossing_direction = ProtoField.string("lhtrace.eci.crossing.direction", "Crossing Direction")
ef.crossing_stage = ProtoField.string("lhtrace.eci.crossing.stage", "Crossing Stage")
ef.crossing_before_source = ProtoField.string("lhtrace.eci.crossing.before_source", "Before Crossing Source")
ef.crossing_after_source = ProtoField.string("lhtrace.eci.crossing.after_source", "After Crossing Source")
ef.crossing_before_stalled_frame = ProtoField.framenum("lhtrace.eci.crossing.before_stalled_frame", "Before Crossing Stalled Frame", base.NONE, frametype.REQUEST)
ef.crossing_before_accepted_frame = ProtoField.framenum("lhtrace.eci.crossing.before_accepted_frame", "Before Crossing Accepted Frame", base.NONE, frametype.RESPONSE)
ef.crossing_after_stalled_frame = ProtoField.framenum("lhtrace.eci.crossing.after_stalled_frame", "After Crossing Stalled Frame", base.NONE, frametype.REQUEST)
ef.crossing_after_accepted_frame = ProtoField.framenum("lhtrace.eci.crossing.after_accepted_frame", "After Crossing Accepted Frame", base.NONE, frametype.RESPONSE)
ef.crossing_app_stalled_frame = ProtoField.framenum("lhtrace.eci.crossing.app_stalled_frame", "App Stalled Frame", base.NONE, frametype.REQUEST)
ef.crossing_app_accepted_frame = ProtoField.framenum("lhtrace.eci.crossing.app_accepted_frame", "App Accepted Frame", base.NONE, frametype.RESPONSE)
ef.crossing_sys_stalled_frame = ProtoField.framenum("lhtrace.eci.crossing.sys_stalled_frame", "Sys Stalled Frame", base.NONE, frametype.REQUEST)
ef.crossing_sys_accepted_frame = ProtoField.framenum("lhtrace.eci.crossing.sys_accepted_frame", "Sys Accepted Frame", base.NONE, frametype.RESPONSE)

local ee = {}
ee.vc_zero = ProtoExpert.new(
    "lhtrace.eci.vc_zero",
    "ECI frame decoded with VC0",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
ee.header_zero = ProtoExpert.new(
    "lhtrace.eci.header_zero",
    "ECI frame decoded with zero header",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
ee.wrong_vc = ProtoExpert.new(
    "lhtrace.eci.wrong_vc",
    "ECI opcode appeared on the wrong VC",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
ee.stall_payload_changed = ProtoExpert.new(
    "lhtrace.eci.stall_payload_changed",
    "ECI payload changed while stalled",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
ee.stalled_never_accepted = ProtoExpert.new(
    "lhtrace.eci.stalled_never_accepted",
    "ECI stalled frame was never accepted",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
ee.crossing_missing_before = ProtoExpert.new(
    "lhtrace.eci.crossing_missing_before",
    "ECI CDC/SLR crossing had no matching before-side frame",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
ee.crossing_missing_after = ProtoExpert.new(
    "lhtrace.eci.crossing_missing_after",
    "ECI CDC/SLR crossing had no matching after-side frame",
    expert.group.PROTOCOL,
    expert.severity.WARN
)
lhtrace.experts = {
    ee.vc_zero,
    ee.header_zero,
    ee.wrong_vc,
    ee.stall_payload_changed,
    ee.stalled_never_accepted,
    ee.crossing_missing_before,
    ee.crossing_missing_after,
}

local evf = lhevent.fields
evf.event_id = ProtoField.uint16("lhtrace.event.id", "Event ID", base.DEC)
evf.event_name = ProtoField.string("lhtrace.event.name", "Event Name")
evf.trace_data = ProtoField.string("lhtrace.event.trace_data", "Trace Data")
evf.trace_data_value = ProtoField.string("lhtrace.event.trace_data.value", "Trace Data Value")

local HEADER_LEN = 50

local ctx = {
    f = f,
    mf = mf,
    sf = sf,
    df = df,
    de = de,
    ef = ef,
    ee = ee,
    evf = evf,
}

local function script_dir()
    local source = debug.getinfo(1, "S").source or ""
    if source:sub(1, 1) == "@" then
        source = source:sub(2)
    end
    local dir = source:match("^(.*[/\\])")
    return dir or ""
end

local module_dir = script_dir() .. "lhtrace/"
local function load_lhtrace_module(name)
    local loader, err = loadfile(module_dir .. name .. ".lua")
    if loader == nil then
        error("failed to load lhtrace module " .. name .. ": " .. tostring(err))
    end
    loader()(ctx)
end

load_lhtrace_module("util")
load_lhtrace_module("metadata")
load_lhtrace_module("flow")
load_lhtrace_module("eci")
load_lhtrace_module("decoders")

ctx.reset_flows()
ctx.reset_eci_pairs()

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
    local subtree = tree:add(packet_protocol.proto, ctx.full_range(tvb), packet_protocol.title)

    if payload_tvb ~= nil then
        if packet_protocol.family == "metadata" then
            local json_text = payload_tvb:string()
            ctx.load_trace_map(json_text, pinfo.visited)
            subtree:add(mf.json, ctx.full_range(payload_tvb))
            info = "Trace map JSON"
        elseif packet_protocol.family == "dcs" then
            local src = ctx.sources_by_id[source] or { type = "unknown" }
            local label = ctx.source_label(src, source)
            pinfo.cols.src = label
            ctx.add_source_info(subtree, tvb, source, label)
            local parsed = ctx.dissect_dcs(payload_tvb, subtree)
            pinfo.cols.dst = parsed.dest
            info = parsed.info
        elseif packet_protocol.family == "eci" then
            local src = ctx.sources_by_id[source] or { type = "unknown" }
            local label = ctx.source_label(src, source)
            pinfo.cols.src = label
            ctx.add_source_info(subtree, tvb, source, label)
            local parsed = ctx.dissect_eci(payload_tvb, subtree, pinfo, source, src)
            pinfo.cols.dst = parsed.dest
            info = parsed.info
        elseif packet_protocol.family == "event" then
            local label = ctx.event_name_from_payload(payload_tvb)
            pinfo.cols.src = label
            ctx.add_source_info(subtree, tvb, source, label)
            local parsed = ctx.dissect_event(payload_tvb, subtree, pinfo, src)
            info = parsed.info
        else
            subtree:add(f.payload, payload_tvb)
        end
    elseif packet_protocol.family == "control" then
        info = (kind_names[kind] or "control") .. " count=" .. tostring(lost_count)
    end

    pinfo.cols.info = info
    subtree:append_text(": " .. info)

    local raw_tree = subtree:add(ctx.full_range(tvb), "Raw Trace Envelope")
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
