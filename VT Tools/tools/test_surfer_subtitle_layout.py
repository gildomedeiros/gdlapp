from datetime import timezone
import re
import unittest
import io

from surfer_subtitle_layout import HEADER,AssWriter,event,card_events,compact_srt,layout,literal,timecode,WIDTH,MARGIN,PAD
from surfer_subtitles import FIELD_IDS,PacketIndex
from test_surfer_subtitles import packet,drones,clock,source_srt


class LayoutTests(unittest.TestCase):
    def test_static_rows_merge_but_changes_and_gaps_remain_exact(self):
        stream=io.StringIO();writer=AssWriter(stream)
        writer.write(event(2,'0:00:00.00','0:00:00.04','unchanged')+event(2,'0:00:00.00','0:00:00.04','GPS age: 0.001'))
        writer.write(event(2,'0:00:00.04','0:00:00.07','unchanged')+event(2,'0:00:00.04','0:00:00.07','GPS age: 0.034'))
        writer.write(event(2,'0:00:00.08','0:00:00.11','unchanged'))
        writer.flush()
        rows=[row.split(',',9) for row in stream.getvalue().splitlines()]
        self.assertEqual(len(rows),4)
        self.assertIn(['0:00:00.00','0:00:00.07'],[row[1:3] for row in rows if row[9]=='unchanged'])
        self.assertIn(['0:00:00.08','0:00:00.11'],[row[1:3] for row in rows if row[9]=='unchanged'])

    def test_all_fields_fit_and_highlights_have_readable_backgrounds(self):
        index=PacketIndex([packet(1000)],drones(),FIELD_IDS)
        added,_,_=index.render(1001,clock(1001),'DJI_0001.MP4','00:00:01,001')
        original=source_srt([1001]).replace('\r\n','\n').strip().split('\n',2)[2]
        lines,height=layout(added,original,16)
        self.assertLess(height,1040)
        for y,_,runs in lines:
            self.assertGreaterEqual(y,MARGIN+PAD)
            for x,width,*_ in runs:self.assertLessEqual(x+width,MARGIN+WIDTH-PAD+.1)
        ass=card_events(1001,1034,added,original)
        self.assertIn('PlayResX: 1920',HEADER)
        self.assertIn('PlayResY: 1080',HEADER)
        self.assertIn(r'\fs16',ass);self.assertIn(r'\pos(32,32)',ass)
        self.assertIn('&Hffffff&',ass)
        self.assertIn('&Hdcf0ff&',ass) # Orange field background.
        self.assertIn('&He9f5e6&',ass) # Green selected/active background.
        self.assertIn('&Hfae8f5&',ass) # Ride detected background.
        self.assertIn('Camera',ass);self.assertIn('metadata',ass)
        self.assertNotIn('<font',ass)
        self.assertTrue(all(',0:00:01.01,0:00:01.04,' in line for line in ass.splitlines()))

    def test_ass_escapes_data_and_does_not_show_new_packet_early(self):
        text=r'{\pos(900,900)}\Nunexpected'
        escaped=literal(text)
        self.assertNotIn('{',escaped);self.assertNotIn(r'\N',escaped)
        for value,expected in [(0,'0:00:00.00'),(33,'0:00:00.04'),(66,'0:00:00.07'),(999,'0:00:01.00'),(3600000,'1:00:00.00')]:
            self.assertEqual(timecode(value),expected)

    def test_compact_srt_retains_source_text_and_exact_timing(self):
        original=source_srt([1000]).replace('\r\n','\n').strip()
        added,_,_=PacketIndex([packet(1000)],drones(),['gps_age']).render(1001,clock(1001),'DJI_0001.MP4','00:00:00,000')
        result=compact_srt(original,added)
        self.assertTrue(result.replace(r'{\an7}','',1).startswith(original+'\n'))
        self.assertTrue(all('size="28"' in font for font in re.findall('<font[^>]*>',added)))


if __name__=='__main__':unittest.main()
