import unittest
from static_path_count import count

ASM = '''
 100: 00000000 bl 200 <helper>
 104: 00000000 cbz x0, 110 <done>
 108: 00000000 nop
 10c: 00000000 b 110 <done>
 110: 00000000 ret
 200: 00000000 subs x0, x0, #1
 204: 00000000 b.ne 200 <helper>
 208: 00000000 ret
'''

class PathCountTest(unittest.TestCase):
    def manifest(self):
        return {'root':'main','nodes':{
            'main':{'image':'test','stage':'RPC','ranges':['100-104','110']},
            'helper':{'image':'test','ranges':['200-204','200-208']}}}

    def test_call_and_selected_loop_iterations(self):
        result=count(self.manifest(), {'test':ASM})
        self.assertEqual(result['instructions'],8)
        self.assertEqual(result['by_node'],{'main':3,'helper':5})
        self.assertEqual([x['taken'] for x in result['branches']],[True,False,True])

    def test_rejects_omitted_instruction(self):
        m=self.manifest();m['nodes']['helper']['ranges']=['200','208']
        with self.assertRaisesRegex(ValueError,'missing fall-through'):
            count(m,{'test':ASM})

    def test_rejects_unbound_callee(self):
        m=self.manifest();del m['nodes']['helper']
        with self.assertRaisesRegex(ValueError,'unbound call'):
            count(m,{'test':ASM})

    def test_rejects_impossible_branch(self):
        m=self.manifest();m['nodes']['main']['ranges']=['100-104','10c-110']
        with self.assertRaisesRegex(ValueError,'impossible conditional edge'):
            count(m,{'test':ASM})

if __name__=='__main__':unittest.main()
