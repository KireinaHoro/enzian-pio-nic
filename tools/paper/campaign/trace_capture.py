"""Classify the known Vivado shutdown crash without hiding trigger failures."""
import re


def trigger_result(returncode, text):
    if returncode == 0:
        return dict(returncode=0,shutdown_crash=False)
    # This exception only permits checking the independent receiver evidence.
    # It does not declare capture success: byte count, SHA256 and the caller's
    # trace/JTAG validation must still pass.
    required=(r'^UDP_DUMP_TRIGGERED$',
              r'^DIAG vio_output \S*/trace_stop 1$',
              r'^TRACE_STATUS trace_dma_error_1 0$',
              r'Closing hw_target ',r'Exiting Vivado at ')
    if (returncode not in (139,-11) or
            not all(re.search(pattern,text,re.M) for pattern in required) or
            re.search(r'^ERROR:',text,re.M)):
        raise RuntimeError(f'Vivado trigger failed (exit {returncode}); preserve receiver evidence')
    if text.rfind('Exiting Vivado at ') < text.rfind('UDP_DUMP_TRIGGERED'):
        raise RuntimeError('Vivado crash was not after trigger completion')
    return dict(returncode=returncode,shutdown_crash=True)
