-- Generator : SpinalHDL dev    git head : 750302cd3da8ae1c10144bafd6252e2be617bf4d
-- Component : SchedTmpl
-- Git hash  : ab11c97907c1475d5414ea6bed2180058698c88a

library IEEE;
use IEEE.STD_LOGIC_1164.ALL;
use IEEE.NUMERIC_STD.all;

package pkg_enum is

end pkg_enum;

library IEEE;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;
use ieee.math_real.all;

package pkg_scala2hdl is
  function pkg_extract (that : std_logic_vector; bitId : integer) return std_logic;
  function pkg_extract (that : std_logic_vector; base : unsigned; size : integer) return std_logic_vector;
  function pkg_cat (a : std_logic_vector; b : std_logic_vector) return std_logic_vector;
  function pkg_not (value : std_logic_vector) return std_logic_vector;
  function pkg_extract (that : unsigned; bitId : integer) return std_logic;
  function pkg_extract (that : unsigned; base : unsigned; size : integer) return unsigned;
  function pkg_cat (a : unsigned; b : unsigned) return unsigned;
  function pkg_not (value : unsigned) return unsigned;
  function pkg_extract (that : signed; bitId : integer) return std_logic;
  function pkg_extract (that : signed; base : unsigned; size : integer) return signed;
  function pkg_cat (a : signed; b : signed) return signed;
  function pkg_not (value : signed) return signed;

  function pkg_mux (sel : std_logic; one : std_logic; zero : std_logic) return std_logic;
  function pkg_mux (sel : std_logic; one : std_logic_vector; zero : std_logic_vector) return std_logic_vector;
  function pkg_mux (sel : std_logic; one : unsigned; zero : unsigned) return unsigned;
  function pkg_mux (sel : std_logic; one : signed; zero : signed) return signed;

  function pkg_toStdLogic (value : boolean) return std_logic;
  function pkg_toStdLogicVector (value : std_logic) return std_logic_vector;
  function pkg_toUnsigned (value : std_logic) return unsigned;
  function pkg_toSigned (value : std_logic) return signed;
  function pkg_stdLogicVector (lit : std_logic_vector) return std_logic_vector;
  function pkg_unsigned (lit : unsigned) return unsigned;
  function pkg_signed (lit : signed) return signed;

  function pkg_resize (that : std_logic_vector; width : integer) return std_logic_vector;
  function pkg_resize (that : unsigned; width : integer) return unsigned;
  function pkg_resize (that : signed; width : integer) return signed;

  function pkg_extract (that : std_logic_vector; high : integer; low : integer) return std_logic_vector;
  function pkg_extract (that : unsigned; high : integer; low : integer) return unsigned;
  function pkg_extract (that : signed; high : integer; low : integer) return signed;

  function pkg_shiftRight (that : std_logic_vector; size : natural) return std_logic_vector;
  function pkg_shiftRight (that : std_logic_vector; size : unsigned) return std_logic_vector;
  function pkg_shiftLeft (that : std_logic_vector; size : natural) return std_logic_vector;
  function pkg_shiftLeft (that : std_logic_vector; size : unsigned) return std_logic_vector;

  function pkg_shiftRight (that : unsigned; size : natural) return unsigned;
  function pkg_shiftRight (that : unsigned; size : unsigned) return unsigned;
  function pkg_shiftLeft (that : unsigned; size : natural) return unsigned;
  function pkg_shiftLeft (that : unsigned; size : unsigned) return unsigned;

  function pkg_shiftRight (that : signed; size : natural) return signed;
  function pkg_shiftRight (that : signed; size : unsigned) return signed;
  function pkg_shiftLeft (that : signed; size : natural) return signed;
  function pkg_shiftLeft (that : signed; size : unsigned; w : integer) return signed;

  function pkg_rotateLeft (that : std_logic_vector; size : unsigned) return std_logic_vector;
end  pkg_scala2hdl;

package body pkg_scala2hdl is
  function pkg_extract (that : std_logic_vector; bitId : integer) return std_logic is
    alias temp : std_logic_vector(that'length-1 downto 0) is that;
  begin
    if bitId >= temp'length then
      return 'U';
    end if;
    return temp(bitId);
  end pkg_extract;

  function pkg_extract (that : std_logic_vector; base : unsigned; size : integer) return std_logic_vector is
    alias temp : std_logic_vector(that'length-1 downto 0) is that;    constant elementCount : integer := temp'length - size + 1;
    type tableType is array (0 to elementCount-1) of std_logic_vector(size-1 downto 0);
    variable table : tableType;
  begin
    for i in 0 to elementCount-1 loop
      table(i) := temp(i + size - 1 downto i);
    end loop;
    if base + size >= elementCount then
      return (size-1 downto 0 => 'U');
    end if;
    return table(to_integer(base));
  end pkg_extract;

  function pkg_cat (a : std_logic_vector; b : std_logic_vector) return std_logic_vector is
    variable cat : std_logic_vector(a'length + b'length-1 downto 0);
  begin
    cat := a & b;
    return cat;
  end pkg_cat;

  function pkg_not (value : std_logic_vector) return std_logic_vector is
    variable ret : std_logic_vector(value'length-1 downto 0);
  begin
    ret := not value;
    return ret;
  end pkg_not;

  function pkg_extract (that : unsigned; bitId : integer) return std_logic is
    alias temp : unsigned(that'length-1 downto 0) is that;
  begin
    if bitId >= temp'length then
      return 'U';
    end if;
    return temp(bitId);
  end pkg_extract;

  function pkg_extract (that : unsigned; base : unsigned; size : integer) return unsigned is
    alias temp : unsigned(that'length-1 downto 0) is that;    constant elementCount : integer := temp'length - size + 1;
    type tableType is array (0 to elementCount-1) of unsigned(size-1 downto 0);
    variable table : tableType;
  begin
    for i in 0 to elementCount-1 loop
      table(i) := temp(i + size - 1 downto i);
    end loop;
    if base + size >= elementCount then
      return (size-1 downto 0 => 'U');
    end if;
    return table(to_integer(base));
  end pkg_extract;

  function pkg_cat (a : unsigned; b : unsigned) return unsigned is
    variable cat : unsigned(a'length + b'length-1 downto 0);
  begin
    cat := a & b;
    return cat;
  end pkg_cat;

  function pkg_not (value : unsigned) return unsigned is
    variable ret : unsigned(value'length-1 downto 0);
  begin
    ret := not value;
    return ret;
  end pkg_not;

  function pkg_extract (that : signed; bitId : integer) return std_logic is
    alias temp : signed(that'length-1 downto 0) is that;
  begin
    if bitId >= temp'length then
      return 'U';
    end if;
    return temp(bitId);
  end pkg_extract;

  function pkg_extract (that : signed; base : unsigned; size : integer) return signed is
    alias temp : signed(that'length-1 downto 0) is that;    constant elementCount : integer := temp'length - size + 1;
    type tableType is array (0 to elementCount-1) of signed(size-1 downto 0);
    variable table : tableType;
  begin
    for i in 0 to elementCount-1 loop
      table(i) := temp(i + size - 1 downto i);
    end loop;
    if base + size >= elementCount then
      return (size-1 downto 0 => 'U');
    end if;
    return table(to_integer(base));
  end pkg_extract;

  function pkg_cat (a : signed; b : signed) return signed is
    variable cat : signed(a'length + b'length-1 downto 0);
  begin
    cat := a & b;
    return cat;
  end pkg_cat;

  function pkg_not (value : signed) return signed is
    variable ret : signed(value'length-1 downto 0);
  begin
    ret := not value;
    return ret;
  end pkg_not;


  -- unsigned shifts
  function pkg_shiftRight (that : unsigned; size : natural) return unsigned is
    variable ret : unsigned(that'length-1 downto 0);
  begin
    if size >= that'length then
      return "";
    else
      ret := shift_right(that,size);
      return ret(that'length-1-size downto 0);
    end if;
  end pkg_shiftRight;

  function pkg_shiftRight (that : unsigned; size : unsigned) return unsigned is
    variable ret : unsigned(that'length-1 downto 0);
  begin
    ret := shift_right(that,to_integer(size));
    return ret;
  end pkg_shiftRight;

  function pkg_shiftLeft (that : unsigned; size : natural) return unsigned is
  begin
    return shift_left(resize(that,that'length + size),size);
  end pkg_shiftLeft;

  function pkg_shiftLeft (that : unsigned; size : unsigned) return unsigned is
  begin
    return shift_left(resize(that,that'length + 2**size'length - 1),to_integer(size));
  end pkg_shiftLeft;

  -- std_logic_vector shifts
  function pkg_shiftRight (that : std_logic_vector; size : natural) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftRight(unsigned(that),size));
  end pkg_shiftRight;

  function pkg_shiftRight (that : std_logic_vector; size : unsigned) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftRight(unsigned(that),size));
  end pkg_shiftRight;

  function pkg_shiftLeft (that : std_logic_vector; size : natural) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftLeft(unsigned(that),size));
  end pkg_shiftLeft;

  function pkg_shiftLeft (that : std_logic_vector; size : unsigned) return std_logic_vector is
  begin
    return std_logic_vector(pkg_shiftLeft(unsigned(that),size));
  end pkg_shiftLeft;

  -- signed shifts
  function pkg_shiftRight (that : signed; size : natural) return signed is
  begin
    return signed(pkg_shiftRight(unsigned(that),size));
  end pkg_shiftRight;

  function pkg_shiftRight (that : signed; size : unsigned) return signed is
  begin
    return shift_right(that,to_integer(size));
  end pkg_shiftRight;

  function pkg_shiftLeft (that : signed; size : natural) return signed is
  begin
    return signed(pkg_shiftLeft(unsigned(that),size));
  end pkg_shiftLeft;

  function pkg_shiftLeft (that : signed; size : unsigned; w : integer) return signed is
  begin
    return shift_left(resize(that,w),to_integer(size));
  end pkg_shiftLeft;

  function pkg_rotateLeft (that : std_logic_vector; size : unsigned) return std_logic_vector is
  begin
    return std_logic_vector(rotate_left(unsigned(that),to_integer(size)));
  end pkg_rotateLeft;

  function pkg_extract (that : std_logic_vector; high : integer; low : integer) return std_logic_vector is
    alias temp : std_logic_vector(that'length-1 downto 0) is that;
  begin
    return temp(high downto low);
  end pkg_extract;

  function pkg_extract (that : unsigned; high : integer; low : integer) return unsigned is
    alias temp : unsigned(that'length-1 downto 0) is that;
  begin
    return temp(high downto low);
  end pkg_extract;

  function pkg_extract (that : signed; high : integer; low : integer) return signed is
    alias temp : signed(that'length-1 downto 0) is that;
  begin
    return temp(high downto low);
  end pkg_extract;

  function pkg_mux (sel : std_logic; one : std_logic; zero : std_logic) return std_logic is
  begin
    if sel = '1' then
      return one;
    else
      return zero;
    end if;
  end pkg_mux;

  function pkg_mux (sel : std_logic; one : std_logic_vector; zero : std_logic_vector) return std_logic_vector is
    variable ret : std_logic_vector(zero'range);
  begin
    if sel = '1' then
      ret := one;
    else
      ret := zero;
    end if;
    return ret;
  end pkg_mux;

  function pkg_mux (sel : std_logic; one : unsigned; zero : unsigned) return unsigned is
    variable ret : unsigned(zero'range);
  begin
    if sel = '1' then
      ret := one;
    else
      ret := zero;
    end if;
    return ret;
  end pkg_mux;

  function pkg_mux (sel : std_logic; one : signed; zero : signed) return signed is
    variable ret : signed(zero'range);
  begin
    if sel = '1' then
      ret := one;
    else
      ret := zero;
    end if;
    return ret;
  end pkg_mux;

  function pkg_toStdLogic (value : boolean) return std_logic is
  begin
    if value = true then
      return '1';
    else
      return '0';
    end if;
  end pkg_toStdLogic;

  function pkg_toStdLogicVector (value : std_logic) return std_logic_vector is
    variable ret : std_logic_vector(0 downto 0);
  begin
    ret(0) := value;
    return ret;
  end pkg_toStdLogicVector;

  function pkg_toUnsigned (value : std_logic) return unsigned is
    variable ret : unsigned(0 downto 0);
  begin
    ret(0) := value;
    return ret;
  end pkg_toUnsigned;

  function pkg_toSigned (value : std_logic) return signed is
    variable ret : signed(0 downto 0);
  begin
    ret(0) := value;
    return ret;
  end pkg_toSigned;

  function pkg_stdLogicVector (lit : std_logic_vector) return std_logic_vector is
    alias ret : std_logic_vector(lit'length-1 downto 0) is lit;
  begin
    return std_logic_vector(ret);
  end pkg_stdLogicVector;

  function pkg_unsigned (lit : unsigned) return unsigned is
    alias ret : unsigned(lit'length-1 downto 0) is lit;
  begin
    return unsigned(ret);
  end pkg_unsigned;

  function pkg_signed (lit : signed) return signed is
    alias ret : signed(lit'length-1 downto 0) is lit;
  begin
    return signed(ret);
  end pkg_signed;

  function pkg_resize (that : std_logic_vector; width : integer) return std_logic_vector is
  begin
    return std_logic_vector(resize(unsigned(that),width));
  end pkg_resize;

  function pkg_resize (that : unsigned; width : integer) return unsigned is
    variable ret : unsigned(width-1 downto 0);
  begin
    if that'length = 0 then
       ret := (others => '0');
    else
       ret := resize(that,width);
    end if;
    return ret;
  end pkg_resize;
  function pkg_resize (that : signed; width : integer) return signed is
    alias temp : signed(that'length-1 downto 0) is that;
    variable ret : signed(width-1 downto 0);
  begin
    if temp'length = 0 then
       ret := (others => '0');
    elsif temp'length >= width then
       ret := temp(width-1 downto 0);
    else
       ret := resize(temp,width);
    end if;
    return ret;
  end pkg_resize;
end pkg_scala2hdl;


library ieee;
use ieee.std_logic_1164.all;
use ieee.numeric_std.all;

library work;
use work.pkg_scala2hdl.all;
use work.all;
use work.pkg_enum.all;


entity SchedTmpl is
  port(
    reset : in std_logic;
    clk : in std_logic
  );

end SchedTmpl;

architecture arch of SchedTmpl is
  signal sched_rxMeta_ready : std_logic;
  signal sched_coreMeta_0_valid : std_logic;
  signal sched_coreMeta_0_payload_funcPtr : std_logic_vector(63 downto 0);
  signal sched_coreMeta_0_payload_pid : std_logic_vector(43 downto 0);
  signal sched_coreMeta_0_payload_args : std_logic_vector(383 downto 0);
  signal sched_coreMeta_0_payload_hdr_xid : std_logic_vector(31 downto 0);
  signal sched_coreMeta_0_payload_hdr_msgType : std_logic_vector(31 downto 0);
  signal sched_coreMeta_0_payload_hdr_rpcVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_0_payload_hdr_progNum : std_logic_vector(31 downto 0);
  signal sched_coreMeta_0_payload_hdr_progVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_0_payload_hdr_proc : std_logic_vector(31 downto 0);
  signal sched_coreMeta_0_payload_hdr_creds : std_logic_vector(63 downto 0);
  signal sched_coreMeta_0_payload_hdr_verifier : std_logic_vector(63 downto 0);
  signal sched_coreMeta_0_payload_udpPayloadSize : unsigned(15 downto 0);
  signal sched_coreMeta_1_valid : std_logic;
  signal sched_coreMeta_1_payload_funcPtr : std_logic_vector(63 downto 0);
  signal sched_coreMeta_1_payload_pid : std_logic_vector(43 downto 0);
  signal sched_coreMeta_1_payload_args : std_logic_vector(383 downto 0);
  signal sched_coreMeta_1_payload_hdr_xid : std_logic_vector(31 downto 0);
  signal sched_coreMeta_1_payload_hdr_msgType : std_logic_vector(31 downto 0);
  signal sched_coreMeta_1_payload_hdr_rpcVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_1_payload_hdr_progNum : std_logic_vector(31 downto 0);
  signal sched_coreMeta_1_payload_hdr_progVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_1_payload_hdr_proc : std_logic_vector(31 downto 0);
  signal sched_coreMeta_1_payload_hdr_creds : std_logic_vector(63 downto 0);
  signal sched_coreMeta_1_payload_hdr_verifier : std_logic_vector(63 downto 0);
  signal sched_coreMeta_1_payload_udpPayloadSize : unsigned(15 downto 0);
  signal sched_coreMeta_2_valid : std_logic;
  signal sched_coreMeta_2_payload_funcPtr : std_logic_vector(63 downto 0);
  signal sched_coreMeta_2_payload_pid : std_logic_vector(43 downto 0);
  signal sched_coreMeta_2_payload_args : std_logic_vector(383 downto 0);
  signal sched_coreMeta_2_payload_hdr_xid : std_logic_vector(31 downto 0);
  signal sched_coreMeta_2_payload_hdr_msgType : std_logic_vector(31 downto 0);
  signal sched_coreMeta_2_payload_hdr_rpcVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_2_payload_hdr_progNum : std_logic_vector(31 downto 0);
  signal sched_coreMeta_2_payload_hdr_progVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_2_payload_hdr_proc : std_logic_vector(31 downto 0);
  signal sched_coreMeta_2_payload_hdr_creds : std_logic_vector(63 downto 0);
  signal sched_coreMeta_2_payload_hdr_verifier : std_logic_vector(63 downto 0);
  signal sched_coreMeta_2_payload_udpPayloadSize : unsigned(15 downto 0);
  signal sched_coreMeta_3_valid : std_logic;
  signal sched_coreMeta_3_payload_funcPtr : std_logic_vector(63 downto 0);
  signal sched_coreMeta_3_payload_pid : std_logic_vector(43 downto 0);
  signal sched_coreMeta_3_payload_args : std_logic_vector(383 downto 0);
  signal sched_coreMeta_3_payload_hdr_xid : std_logic_vector(31 downto 0);
  signal sched_coreMeta_3_payload_hdr_msgType : std_logic_vector(31 downto 0);
  signal sched_coreMeta_3_payload_hdr_rpcVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_3_payload_hdr_progNum : std_logic_vector(31 downto 0);
  signal sched_coreMeta_3_payload_hdr_progVer : std_logic_vector(31 downto 0);
  signal sched_coreMeta_3_payload_hdr_proc : std_logic_vector(31 downto 0);
  signal sched_coreMeta_3_payload_hdr_creds : std_logic_vector(63 downto 0);
  signal sched_coreMeta_3_payload_hdr_verifier : std_logic_vector(63 downto 0);
  signal sched_coreMeta_3_payload_udpPayloadSize : unsigned(15 downto 0);
  signal sched_corePreempt_0_valid : std_logic;
  signal sched_corePreempt_0_payload : std_logic_vector(43 downto 0);
  signal sched_corePreempt_1_valid : std_logic;
  signal sched_corePreempt_1_payload : std_logic_vector(43 downto 0);
  signal sched_corePreempt_2_valid : std_logic;
  signal sched_corePreempt_2_payload : std_logic_vector(43 downto 0);
  signal sched_corePreempt_3_valid : std_logic;
  signal sched_corePreempt_3_payload : std_logic_vector(43 downto 0);
  signal sched_createThread_ready : std_logic;
  signal sched_destroyProcess_ready : std_logic;

  component Scheduler is
    generic( 
      NUM_CORES : integer ;
      PID_QUEUE_DEPTH : integer ;
      PID_WIDTH : integer ;
      TID_WIDTH : integer  
    );
    port( 
      clk : in std_logic;
      rst : in std_logic;
      rxMeta_valid : in std_logic;
      rxMeta_ready : out std_logic;
      rxMeta_payload_funcPtr : in std_logic_vector;
      rxMeta_payload_pid : in std_logic_vector;
      rxMeta_payload_args : in std_logic_vector;
      rxMeta_payload_hdr_xid : in std_logic_vector;
      rxMeta_payload_hdr_msgType : in std_logic_vector;
      rxMeta_payload_hdr_rpcVer : in std_logic_vector;
      rxMeta_payload_hdr_progNum : in std_logic_vector;
      rxMeta_payload_hdr_progVer : in std_logic_vector;
      rxMeta_payload_hdr_proc : in std_logic_vector;
      rxMeta_payload_hdr_creds : in std_logic_vector;
      rxMeta_payload_hdr_verifier : in std_logic_vector;
      rxMeta_payload_udpPayloadSize : in unsigned;
      coreMeta_0_valid : out std_logic;
      coreMeta_0_ready : in std_logic;
      coreMeta_0_payload_funcPtr : out std_logic_vector;
      coreMeta_0_payload_pid : out std_logic_vector;
      coreMeta_0_payload_args : out std_logic_vector;
      coreMeta_0_payload_hdr_xid : out std_logic_vector;
      coreMeta_0_payload_hdr_msgType : out std_logic_vector;
      coreMeta_0_payload_hdr_rpcVer : out std_logic_vector;
      coreMeta_0_payload_hdr_progNum : out std_logic_vector;
      coreMeta_0_payload_hdr_progVer : out std_logic_vector;
      coreMeta_0_payload_hdr_proc : out std_logic_vector;
      coreMeta_0_payload_hdr_creds : out std_logic_vector;
      coreMeta_0_payload_hdr_verifier : out std_logic_vector;
      coreMeta_0_payload_udpPayloadSize : out unsigned;
      coreMeta_1_valid : out std_logic;
      coreMeta_1_ready : in std_logic;
      coreMeta_1_payload_funcPtr : out std_logic_vector;
      coreMeta_1_payload_pid : out std_logic_vector;
      coreMeta_1_payload_args : out std_logic_vector;
      coreMeta_1_payload_hdr_xid : out std_logic_vector;
      coreMeta_1_payload_hdr_msgType : out std_logic_vector;
      coreMeta_1_payload_hdr_rpcVer : out std_logic_vector;
      coreMeta_1_payload_hdr_progNum : out std_logic_vector;
      coreMeta_1_payload_hdr_progVer : out std_logic_vector;
      coreMeta_1_payload_hdr_proc : out std_logic_vector;
      coreMeta_1_payload_hdr_creds : out std_logic_vector;
      coreMeta_1_payload_hdr_verifier : out std_logic_vector;
      coreMeta_1_payload_udpPayloadSize : out unsigned;
      coreMeta_2_valid : out std_logic;
      coreMeta_2_ready : in std_logic;
      coreMeta_2_payload_funcPtr : out std_logic_vector;
      coreMeta_2_payload_pid : out std_logic_vector;
      coreMeta_2_payload_args : out std_logic_vector;
      coreMeta_2_payload_hdr_xid : out std_logic_vector;
      coreMeta_2_payload_hdr_msgType : out std_logic_vector;
      coreMeta_2_payload_hdr_rpcVer : out std_logic_vector;
      coreMeta_2_payload_hdr_progNum : out std_logic_vector;
      coreMeta_2_payload_hdr_progVer : out std_logic_vector;
      coreMeta_2_payload_hdr_proc : out std_logic_vector;
      coreMeta_2_payload_hdr_creds : out std_logic_vector;
      coreMeta_2_payload_hdr_verifier : out std_logic_vector;
      coreMeta_2_payload_udpPayloadSize : out unsigned;
      coreMeta_3_valid : out std_logic;
      coreMeta_3_ready : in std_logic;
      coreMeta_3_payload_funcPtr : out std_logic_vector;
      coreMeta_3_payload_pid : out std_logic_vector;
      coreMeta_3_payload_args : out std_logic_vector;
      coreMeta_3_payload_hdr_xid : out std_logic_vector;
      coreMeta_3_payload_hdr_msgType : out std_logic_vector;
      coreMeta_3_payload_hdr_rpcVer : out std_logic_vector;
      coreMeta_3_payload_hdr_progNum : out std_logic_vector;
      coreMeta_3_payload_hdr_progVer : out std_logic_vector;
      coreMeta_3_payload_hdr_proc : out std_logic_vector;
      coreMeta_3_payload_hdr_creds : out std_logic_vector;
      coreMeta_3_payload_hdr_verifier : out std_logic_vector;
      coreMeta_3_payload_udpPayloadSize : out unsigned;
      corePreempt_0_valid : out std_logic;
      corePreempt_0_ready : in std_logic;
      corePreempt_0_payload : out std_logic_vector;
      corePreempt_1_valid : out std_logic;
      corePreempt_1_ready : in std_logic;
      corePreempt_1_payload : out std_logic_vector;
      corePreempt_2_valid : out std_logic;
      corePreempt_2_ready : in std_logic;
      corePreempt_2_payload : out std_logic_vector;
      corePreempt_3_valid : out std_logic;
      corePreempt_3_ready : in std_logic;
      corePreempt_3_payload : out std_logic_vector;
      createThread_valid : in std_logic;
      createThread_ready : out std_logic;
      createThread_payload_pid : in std_logic_vector;
      createThread_payload_tid : in std_logic_vector;
      destroyProcess_valid : in std_logic;
      destroyProcess_ready : out std_logic;
      destroyProcess_payload : in std_logic_vector 
    );
  end component;
  

begin
  sched : Scheduler
    generic map( 
      NUM_CORES => 4,
      PID_QUEUE_DEPTH => 128,
      PID_WIDTH => 44,
      TID_WIDTH => 44 
    )
    port map ( 
      clk => clk,
      rst => reset,
      rxMeta_valid => pkg_toStdLogic(false),
      rxMeta_ready => sched_rxMeta_ready,
      rxMeta_payload_funcPtr => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_pid => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_args => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_xid => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_msgType => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_rpcVer => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_progNum => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_progVer => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_proc => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_creds => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_hdr_verifier => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      rxMeta_payload_udpPayloadSize => pkg_unsigned("XXXXXXXXXXXXXXXX"),
      coreMeta_0_valid => sched_coreMeta_0_valid,
      coreMeta_0_ready => pkg_toStdLogic(false),
      coreMeta_0_payload_funcPtr => sched_coreMeta_0_payload_funcPtr,
      coreMeta_0_payload_pid => sched_coreMeta_0_payload_pid,
      coreMeta_0_payload_args => sched_coreMeta_0_payload_args,
      coreMeta_0_payload_hdr_xid => sched_coreMeta_0_payload_hdr_xid,
      coreMeta_0_payload_hdr_msgType => sched_coreMeta_0_payload_hdr_msgType,
      coreMeta_0_payload_hdr_rpcVer => sched_coreMeta_0_payload_hdr_rpcVer,
      coreMeta_0_payload_hdr_progNum => sched_coreMeta_0_payload_hdr_progNum,
      coreMeta_0_payload_hdr_progVer => sched_coreMeta_0_payload_hdr_progVer,
      coreMeta_0_payload_hdr_proc => sched_coreMeta_0_payload_hdr_proc,
      coreMeta_0_payload_hdr_creds => sched_coreMeta_0_payload_hdr_creds,
      coreMeta_0_payload_hdr_verifier => sched_coreMeta_0_payload_hdr_verifier,
      coreMeta_0_payload_udpPayloadSize => sched_coreMeta_0_payload_udpPayloadSize,
      coreMeta_1_valid => sched_coreMeta_1_valid,
      coreMeta_1_ready => pkg_toStdLogic(false),
      coreMeta_1_payload_funcPtr => sched_coreMeta_1_payload_funcPtr,
      coreMeta_1_payload_pid => sched_coreMeta_1_payload_pid,
      coreMeta_1_payload_args => sched_coreMeta_1_payload_args,
      coreMeta_1_payload_hdr_xid => sched_coreMeta_1_payload_hdr_xid,
      coreMeta_1_payload_hdr_msgType => sched_coreMeta_1_payload_hdr_msgType,
      coreMeta_1_payload_hdr_rpcVer => sched_coreMeta_1_payload_hdr_rpcVer,
      coreMeta_1_payload_hdr_progNum => sched_coreMeta_1_payload_hdr_progNum,
      coreMeta_1_payload_hdr_progVer => sched_coreMeta_1_payload_hdr_progVer,
      coreMeta_1_payload_hdr_proc => sched_coreMeta_1_payload_hdr_proc,
      coreMeta_1_payload_hdr_creds => sched_coreMeta_1_payload_hdr_creds,
      coreMeta_1_payload_hdr_verifier => sched_coreMeta_1_payload_hdr_verifier,
      coreMeta_1_payload_udpPayloadSize => sched_coreMeta_1_payload_udpPayloadSize,
      coreMeta_2_valid => sched_coreMeta_2_valid,
      coreMeta_2_ready => pkg_toStdLogic(false),
      coreMeta_2_payload_funcPtr => sched_coreMeta_2_payload_funcPtr,
      coreMeta_2_payload_pid => sched_coreMeta_2_payload_pid,
      coreMeta_2_payload_args => sched_coreMeta_2_payload_args,
      coreMeta_2_payload_hdr_xid => sched_coreMeta_2_payload_hdr_xid,
      coreMeta_2_payload_hdr_msgType => sched_coreMeta_2_payload_hdr_msgType,
      coreMeta_2_payload_hdr_rpcVer => sched_coreMeta_2_payload_hdr_rpcVer,
      coreMeta_2_payload_hdr_progNum => sched_coreMeta_2_payload_hdr_progNum,
      coreMeta_2_payload_hdr_progVer => sched_coreMeta_2_payload_hdr_progVer,
      coreMeta_2_payload_hdr_proc => sched_coreMeta_2_payload_hdr_proc,
      coreMeta_2_payload_hdr_creds => sched_coreMeta_2_payload_hdr_creds,
      coreMeta_2_payload_hdr_verifier => sched_coreMeta_2_payload_hdr_verifier,
      coreMeta_2_payload_udpPayloadSize => sched_coreMeta_2_payload_udpPayloadSize,
      coreMeta_3_valid => sched_coreMeta_3_valid,
      coreMeta_3_ready => pkg_toStdLogic(false),
      coreMeta_3_payload_funcPtr => sched_coreMeta_3_payload_funcPtr,
      coreMeta_3_payload_pid => sched_coreMeta_3_payload_pid,
      coreMeta_3_payload_args => sched_coreMeta_3_payload_args,
      coreMeta_3_payload_hdr_xid => sched_coreMeta_3_payload_hdr_xid,
      coreMeta_3_payload_hdr_msgType => sched_coreMeta_3_payload_hdr_msgType,
      coreMeta_3_payload_hdr_rpcVer => sched_coreMeta_3_payload_hdr_rpcVer,
      coreMeta_3_payload_hdr_progNum => sched_coreMeta_3_payload_hdr_progNum,
      coreMeta_3_payload_hdr_progVer => sched_coreMeta_3_payload_hdr_progVer,
      coreMeta_3_payload_hdr_proc => sched_coreMeta_3_payload_hdr_proc,
      coreMeta_3_payload_hdr_creds => sched_coreMeta_3_payload_hdr_creds,
      coreMeta_3_payload_hdr_verifier => sched_coreMeta_3_payload_hdr_verifier,
      coreMeta_3_payload_udpPayloadSize => sched_coreMeta_3_payload_udpPayloadSize,
      corePreempt_0_valid => sched_corePreempt_0_valid,
      corePreempt_0_ready => pkg_toStdLogic(false),
      corePreempt_0_payload => sched_corePreempt_0_payload,
      corePreempt_1_valid => sched_corePreempt_1_valid,
      corePreempt_1_ready => pkg_toStdLogic(false),
      corePreempt_1_payload => sched_corePreempt_1_payload,
      corePreempt_2_valid => sched_corePreempt_2_valid,
      corePreempt_2_ready => pkg_toStdLogic(false),
      corePreempt_2_payload => sched_corePreempt_2_payload,
      corePreempt_3_valid => sched_corePreempt_3_valid,
      corePreempt_3_ready => pkg_toStdLogic(false),
      corePreempt_3_payload => sched_corePreempt_3_payload,
      createThread_valid => pkg_toStdLogic(false),
      createThread_ready => sched_createThread_ready,
      createThread_payload_pid => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      createThread_payload_tid => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"),
      destroyProcess_valid => pkg_toStdLogic(false),
      destroyProcess_ready => sched_destroyProcess_ready,
      destroyProcess_payload => pkg_stdLogicVector("XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX") 
    );
end arch;

