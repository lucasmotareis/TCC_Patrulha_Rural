import json
from pathlib import Path
import tempfile
import unittest
import osmium
from merge_batalhao_roads import merge, TO_WGS84


class ConnectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory()
        self.root=Path(self.tmp.name)
        self.base=self.root/'base.osm.pbf'
        self.origin=(700000,8870000)
        self.xy=lambda x,y:TO_WGS84(self.origin[0]+x,self.origin[1]+y)
        with osmium.SimpleWriter(str(self.base)) as writer:
            for n,(x,y) in enumerate([(0,0),(100,0),(100,100)],1):
                writer.add_node(osmium.osm.mutable.Node(id=n,location=self.xy(x,y)))
            writer.add_way(osmium.osm.mutable.Way(id=10,nodes=[1,2],tags={'highway':'unclassified','oneway':'yes'}))
            writer.add_way(osmium.osm.mutable.Way(id=11,nodes=[2,3],tags={'highway':'unclassified'}))
            writer.add_relation(osmium.osm.mutable.Relation(id=20,tags={'type':'restriction','restriction':'no_right_turn'},members=[('w',10,'from'),('n',2,'via'),('w',11,'to')]))

    def tearDown(self):
        self.tmp.cleanup()

    def run_merge(self,coords):
        roads=self.root/'roads.geojson'
        roads.write_text(json.dumps({'type':'FeatureCollection','features':[{'type':'Feature','properties':{'nome':'Acesso teste','highway':'service','access':'yes','oneway':'no','status':'revisado'},'geometry':{'type':'LineString','coordinates':[self.xy(*p) for p in coords]}}]}),encoding='utf-8')
        return merge(self.base,roads,self.root/'merged.osm.pbf',self.root/'report.json')

    def test_midsegment_uses_shared_node_and_preserves_restrictions(self):
        report=self.run_merge([(50,2),(50,40)])
        nodes,ways,relations={}, {}, []
        order=[]
        for obj in osmium.FileProcessor(str(self.root/'merged.osm.pbf')):
            if obj.is_node():
                nodes[obj.id]=(obj.location.lon,obj.location.lat); order.append((0,obj.id))
            elif obj.is_way():
                ways[obj.id]=([n.ref for n in obj.nodes],dict(obj.tags)); order.append((1,obj.id))
            else:
                relations.append([(m.type,m.ref,m.role) for m in obj.members]); order.append((2,obj.id))
        shared=report['connections'][0]['shared_node_id']
        self.assertEqual(ways[10][0],[1,shared,2])
        self.assertEqual(ways[12][0][0],shared)
        self.assertEqual(ways[10][1]['oneway'],'yes')
        self.assertEqual(relations,[[('w',10,'from'),('n',2,'via'),('w',11,'to')]])
        self.assertEqual(order,sorted(order))
        self.assertTrue(all(ref in nodes for refs,_ in ways.values() for ref in refs))

    def test_disconnected_road_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'neither endpoint'):
            self.run_merge([(40,30),(40,60)])
        self.assertFalse((self.root/'merged.osm.pbf').exists())

    def test_duplicate_existing_road_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'mostly overlaps'):
            self.run_merge([(10,0),(90,0)])
        self.assertFalse((self.root/'merged.osm.pbf').exists())

    def test_unreviewed_interior_crossing_is_rejected(self):
        with self.assertRaisesRegex(ValueError,'Interior crossings'):
            self.run_merge([(0,0),(50,20),(50,-20)])
        self.assertFalse((self.root/'merged.osm.pbf').exists())


if __name__=='__main__':
    unittest.main()
