import io
import unittest
from vtc_subtitle_layout import AssWriter,event


class OrderTests(unittest.TestCase):
    def test_long_held_panel_and_static_text_are_written_before_later_clocks(self):
        out=io.StringIO();writer=AssWriter(out)
        writer.write(event(0,'0:00:00.00','0:00:01.00','panel')+event(2,'0:00:00.00','0:00:01.00','static')+event(2,'0:00:00.00','0:00:01.00','clock 0'))
        writer.write(event(0,'0:00:01.00','0:00:02.00','panel')+event(2,'0:00:01.00','0:00:02.00','static')+event(2,'0:00:01.00','0:00:02.00','clock 1'))
        writer.write(event(0,'0:00:02.00','0:00:03.00','panel')+event(2,'0:00:02.00','0:00:03.00','static')+event(2,'0:00:02.00','0:00:03.00','clock 2'))
        writer.flush();rows=[line.split(',',9) for line in out.getvalue().splitlines()]
        self.assertEqual([r[1] for r in rows],sorted(r[1] for r in rows))
        self.assertEqual(rows[0][9],'panel');self.assertEqual(rows[0][2],'0:00:03.00')
        self.assertEqual(len(rows),5)
        before=out.getvalue();writer.flush();self.assertEqual(out.getvalue(),before)


if __name__=='__main__':unittest.main()
