---------------------------- MODULE DcMachine ----------------------------
EXTENDS Naturals
CONSTANT DcTable
\* Selected ROM transitions, mechanically extracted from its generated SV.
\* No directory allocation/arbitration here: one already admitted line.
Enabled(s,e) == {t \in DcTable: t.state = s /\ t.event = e /\ t.action # "STALL"}
Step(s,e) == CHOOSE t \in Enabled(s,e): TRUE
Legal(s,e) == Enabled(s,e) # {} /\ Step(s,e).action # "NOT_ALLOWED"
=============================================================================
