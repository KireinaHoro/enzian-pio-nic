library ieee;
use ieee.std_logic_1164.all;
use std.env.all;

entity gsync_release_cdc_tb is end;
architecture test of gsync_release_cdc_tb is
    signal sys_clk : std_logic := '0';
    signal app_clk : std_logic := '0';
    signal rst : std_logic := '1';
    signal observed, release_rx : std_logic := '0';
    signal deliveries : natural := 0;
begin
    sys_clk <= not sys_clk after 2 ns;
    app_clk <= not app_clk after 3.5 ns;
    dut : entity work.gsync_release_cdc
        port map(sys_clk, rst, observed, app_clk, rst, release_rx);
    process(app_clk)
        variable previous : std_logic := '0';
    begin
        if rising_edge(app_clk) then
            if rst = '1' then
                previous := '0';
            else
                assert not (release_rx = '1' and previous = '1')
                    report "release must last exactly one app cycle" severity failure;
                if release_rx = '1' then deliveries <= deliveries + 1; end if;
                previous := release_rx;
            end if;
        end if;
    end process;
    process
        variable before : natural;
    begin
        wait for 40 ns; rst <= '0';
        -- Single source pulses at varying phases, separated by completed handshakes.
        for phase in 0 to 15 loop
            wait for phase * 1 ns;
            before := deliveries;
            wait until falling_edge(sys_clk); observed <= '1';
            wait until falling_edge(sys_clk); observed <= '0';
            wait for 160 ns;
            assert deliveries = before + 1 report "missing or duplicate isolated event" severity failure;
        end loop;
        -- Continuous/bursty GSYNC arrivals, including the acknowledgement race.
        before := deliveries;
        for i in 0 to 39 loop
            wait until falling_edge(sys_clk); observed <= '1';
            wait until falling_edge(sys_clk); observed <= '0';
        end loop;
        wait for 160 ns;
        assert deliveries > before + 1 report "burst was not delivered" severity failure;
        before := deliveries;
        wait for 200 ns;
        assert deliveries = before and release_rx = '0'
            report "release remained active after traffic stopped" severity failure;
        report "GSYNC CDC pulse tests passed";
        stop;
        wait;
    end process;
end architecture;
