---------------------------- MODULE ThxMachine ----------------------------
EXTENDS Naturals
CONSTANT ThxTable
\* All extracted remote-FSM transitions. Buffers/credits and data values are
\* not described by these equations; neither is a separate dirty cache state.
Enabled(s,p,m) == {t \in ThxTable: t.state = s /\ t.port = p /\ t.msg = m}
Step(s,p,m) == CHOOSE t \in Enabled(s,p,m): TRUE
=============================================================================
