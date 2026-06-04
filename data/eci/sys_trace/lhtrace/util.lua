return function(ctx)
local _ENV = setmetatable(ctx, { __index = _G })

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

local function int_text(value)
    local n = tonumber(value)
    if n ~= nil then
        return string.format("%.0f", n)
    end
    local text = tostring(value)
    n = tonumber(text)
    if n ~= nil then
        return string.format("%.0f", n)
    end
    local integer = text:match("^(%-?%d+)%.0")
    if integer ~= nil then
        return integer
    end
    return text
end

local function flow_text(text)
    return tostring(text):gsub("(%d+)%.0", "%1")
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

local function full_range(tvb)
    return tvb(0, tvb:len())
end

local function anchor_range(tvb)
    if tvb:len() > 0 then
        return tvb(0, 1)
    end
    return tvb(0, 0)
end

local function tvb_has_bits(tvb, bit_offset, width)
    if bit_offset == nil or width == nil then
        return false
    end
    return math.ceil((bit_offset + width) / 8) <= tvb:len()
end

local function validate_payload_fields(payload_tvb, fields, names, source_type)
    for _, name in ipairs(names) do
        local field = fields[name]
        if field == nil then
            return false, "missing metadata for " .. tostring(source_type) .. "." .. tostring(name)
        end
        local offset = tonumber(field.offset)
        local width = tonumber(field.width)
        if not tvb_has_bits(payload_tvb, offset, width) then
            return false, tostring(source_type) .. "." .. tostring(name) .. " extends past payload"
        end
    end
    return true, nil
end

local function malformed_payload_result(tree, payload_tvb, label, reason)
    local info = "Malformed " .. label .. " payload"
    if reason ~= nil then
        info = info .. ": " .. reason
    end
    tree:add(full_range(payload_tvb), info)
    return {
        info = info,
        source = label,
        dest = "",
    }
end

local function bit_range_num(value, hi, lo)
    return math.floor(value / (2 ^ lo)) % (2 ^ (hi - lo + 1))
end

local function bxor(...)
    local args = { ... }
    local result = 0
    local bit_value = 1
    local remaining = true
    while remaining do
        local parity = 0
        remaining = false
        for index, value in ipairs(args) do
            value = math.floor(value)
            if value > 0 then
                remaining = true
            end
            parity = (parity + (value % 2)) % 2
            args[index] = math.floor(value / 2)
        end
        if parity ~= 0 then
            result = result + bit_value
        end
        bit_value = bit_value * 2
    end
    return result
end

local function unalias_address(aliased_addr)
    local aliased_cli = bit_range_num(aliased_addr, 39, 7)
    local byte_offset = bit_range_num(aliased_addr, 6, 0)
    local cli = 0
    cli = cli + bit_range_num(aliased_cli, 32, 13) * (2 ^ 13)
    cli = cli + bxor(bit_range_num(aliased_cli, 12, 8), bit_range_num(aliased_cli, 17, 13)) * (2 ^ 8)
    cli = cli + bxor(bit_range_num(aliased_cli, 7, 5), bit_range_num(aliased_cli, 20, 18)) * (2 ^ 5)
    cli = cli + bxor(
        bit_range_num(aliased_cli, 4, 3)
        , bit_range_num(aliased_cli, 19, 18)
        , bit_range_num(aliased_cli, 17, 16)
        , bit_range_num(aliased_cli, 6, 5)
    ) * (2 ^ 3)
    cli = cli + bxor(
        bit_range_num(aliased_cli, 2, 0)
        , bit_range_num(aliased_cli, 20, 18)
        , bit_range_num(aliased_cli, 15, 13)
        , bit_range_num(aliased_cli, 7, 5)
    )
    return cli * 128 + byte_offset
end

local function fmt_addr(value)
    return string.format("0x%010x", value)
end

ctx.json_decode = json_decode
ctx.append_unique = append_unique
ctx.int_text = int_text
ctx.flow_text = flow_text
ctx.extract_bits_le = extract_bits_le
ctx.byte_range_for_bits = byte_range_for_bits
ctx.full_range = full_range
ctx.anchor_range = anchor_range
ctx.tvb_has_bits = tvb_has_bits
ctx.validate_payload_fields = validate_payload_fields
ctx.malformed_payload_result = malformed_payload_result
ctx.bit_range_num = bit_range_num
ctx.unalias_address = unalias_address
ctx.fmt_addr = fmt_addr

end
