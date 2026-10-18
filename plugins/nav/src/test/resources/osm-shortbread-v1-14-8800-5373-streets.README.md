# OpenStreetMap Shortbread sample tile

`osm-shortbread-v1-14-8800-5373-streets.mvt.gz` is one public vector tile, reduced
to the two layers Navigation reads.

- Source: `https://vector.openstreetmap.org/shortbread_v1/14/8800/5373.mvt`, the
  OpenStreetMap Foundation's vector tile service, fetched once on 2026-10-18 under
  its [usage policy](https://operations.osmfoundation.org/policies/vector/).
  Response `Last-Modified: Sun, 18 Oct 2026 05:10:24 GMT`; the gzip body as served
  had SHA-256 `44574923354e56230f2fb5fc5fa8fbb3a8dfd3dc87d5ef70c32ca21145e51933`.
- Area: zoom 14 tile around the Brandenburg Gate, Berlin (13.359–13.381 E,
  52.510–52.523 N). A public landmark, unrelated to any user's route.
- Reduction: the `streets` and `street_labels` layer messages were copied byte for
  byte from the inflated tile, every other layer dropped, and the result gzipped.
  No feature, tag or coordinate was changed.
- Independent counts (a separate Python protobuf reader): `streets` 1,635 line
  features with 6,135 vertices, 925 of kind `footway` and 18 `primary`;
  `street_labels` 1,328 line features, 185 of them named.

Map data © OpenStreetMap contributors, available under the
[Open Database License](https://www.openstreetmap.org/copyright). Schema:
[Shortbread 1.0](https://shortbread-tiles.org/schema/1.0/).
