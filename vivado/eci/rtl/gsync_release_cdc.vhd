library ieee;
use ieee.std_logic_1164.all;

-- Coalescing level handshake, not a toggle: arbitrarily close GSYNCs cannot
-- cancel each other. No dependency from this observer to the GSDN responder.
-- Requests are held across CDC, but each delivery emits just one app-clock
-- pulse. Events during acknowledgement are coalesced into the next delivery.
entity gsync_release_cdc is
    port (
        sys_clk, sys_reset, observed : in std_logic;
        app_clk, app_reset : in std_logic;
        release_rx : out std_logic
    );
end entity;

architecture rtl of gsync_release_cdc is
    signal pending, queued : std_logic := '0';
    signal req_meta, req_sync : std_logic := '0';
    signal req_previous : std_logic := '0';
    signal ack_meta, ack_sync : std_logic := '0';
    attribute ASYNC_REG : string;
    attribute ASYNC_REG of req_meta, req_sync, ack_meta, ack_sync : signal is "TRUE";
begin
    process(sys_clk)
    begin
        if rising_edge(sys_clk) then
            if sys_reset = '1' then
                pending <= '0';
                queued <= '0';
                ack_meta <= '0';
                ack_sync <= '0';
            else
                ack_meta <= req_sync;
                ack_sync <= ack_meta;
                if observed = '1' then
                    queued <= '1';
                end if;
                if pending = '1' then
                    if ack_sync = '1' then
                        pending <= '0';
                    end if;
                elsif ack_sync = '0' and (queued = '1' or observed = '1') then
                    pending <= '1';
                    queued <= '0';
                end if;
            end if;
        end if;
    end process;
    process(app_clk)
    begin
        if rising_edge(app_clk) then
            if app_reset = '1' then
                req_meta <= '0';
                req_sync <= '0';
                req_previous <= '0';
            else
                req_meta <= pending;
                req_sync <= req_meta;
                req_previous <= req_sync;
            end if;
        end if;
    end process;
    release_rx <= req_sync and not req_previous;
end architecture;
