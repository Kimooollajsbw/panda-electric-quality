"""Convert a 3MF exported from SolidWorks into a GLB with real PBR materials per part.

SolidWorks writes the assembly tree (component objects with transforms pointing at mesh bodies)
but no colours, so each part gets a material by matching its name against MATERIAL_RULES below
(taken from the product's bill of materials).

Output is standard glTF: metres, Y up, the product centred on X/Z and standing on Y = 0.

Usage: python tools/threemf_to_glb.py input.3mf output.glb [--skip NAME_PART ...]
Standard library only.
"""

import argparse
import json
import math
import struct
import sys
import zipfile
import xml.etree.ElementTree as ElementTree

CORE_NAMESPACE = "{http://schemas.microsoft.com/3dmanufacturing/core/2015/02}"
SMOOTHING_ANGLE_DEGREES = 35.0

# (substring of the part name, material name, linear RGB, metallic, roughness, emissive RGB or None)
MATERIAL_RULES = [
    ("LED_3MM", "led_blue", (0.05, 0.25, 1.0), 0.0, 0.2, (0.1, 0.35, 1.0)),
    ("IEC_C14_INLET", "black_pa66", (0.025, 0.025, 0.027), 0.0, 0.55, None),
    ("INLET_PIN", "nickel_brass", (0.66, 0.63, 0.58), 1.0, 0.3, None),
    ("SCHUKO_EARTH_CLIP", "brass", (0.86, 0.66, 0.32), 1.0, 0.32, None),
    ("BUSBAR", "tin_copper", (0.74, 0.74, 0.72), 1.0, 0.38, None),
    ("JUMPER", "copper", (0.95, 0.62, 0.5), 1.0, 0.3, None),
    ("BLADE_PIN", "silver", (0.95, 0.93, 0.88), 1.0, 0.25, None),
    ("USB-A_PORT", "steel", (0.62, 0.62, 0.63), 1.0, 0.32, None),
    ("USB-C_PORT", "steel", (0.62, 0.62, 0.63), 1.0, 0.32, None),
    ("SCREW", "zinc_steel", (0.7, 0.72, 0.75), 1.0, 0.4, None),
    ("BOARD", "fr4_green", (0.03, 0.17, 0.06), 0.0, 0.45, None),
    ("INSULATION_BARRIER", "fish_paper", (0.12, 0.12, 0.13), 0.0, 0.8, None),
    ("LIPO_CELL", "cell_foil", (0.72, 0.72, 0.74), 0.6, 0.45, None),
    ("LIGHT_PIPE", "clear_pc", (0.92, 0.92, 0.92), 0.0, 0.08, None),
    ("CORD_PLUG", "white_pvc", (0.82, 0.82, 0.8), 0.0, 0.6, None),
    ("ROCKER_SWITCH", "white_pa66", (0.84, 0.84, 0.82), 0.0, 0.4, None),
    ("", "white_pc", (0.86, 0.86, 0.84), 0.0, 0.42, None),
]


def material_for(part_name):
    upper_name = part_name.upper()
    for name_fragment, material_name, base_color, metallic, roughness, emissive in MATERIAL_RULES:
        if name_fragment in upper_name:
            return material_name, base_color, metallic, roughness, emissive
    raise ValueError(part_name)


def parse_transform(transform_text):
    """3MF transform: 12 numbers, row-vector convention p' = [x y z 1] * M."""
    if not transform_text:
        return [1, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0]
    return [float(number) for number in transform_text.split()]


def combine(outer, inner):
    """Transform applying `inner` first, then `outer` (both 3MF 4x3 row-vector matrices)."""
    def as_matrix(values):
        return [values[0:3] + [0.0], values[3:6] + [0.0], values[6:9] + [0.0], values[9:12] + [1.0]]
    inner_matrix = as_matrix(inner)
    outer_matrix = as_matrix(outer)
    product = [[sum(inner_matrix[row][k] * outer_matrix[k][column] for k in range(4)) for column in range(4)] for row in range(4)]
    return product[0][:3] + product[1][:3] + product[2][:3] + product[3][:3]


def apply_transform(transform, point):
    x, y, z = point
    return (
        x * transform[0] + y * transform[3] + z * transform[6] + transform[9],
        x * transform[1] + y * transform[4] + z * transform[7] + transform[10],
        x * transform[2] + y * transform[5] + z * transform[8] + transform[11],
    )


def read_model(path):
    with zipfile.ZipFile(path) as archive:
        model_name = next(name for name in archive.namelist() if name.endswith(".model"))
        root = ElementTree.fromstring(archive.read(model_name))
    unit = root.get("unit", "millimeter")
    meshes = {}
    components = {}
    names = {}
    for object_element in root.iter(CORE_NAMESPACE + "object"):
        object_id = object_element.get("id")
        names[object_id] = object_element.get("name", object_id)
        mesh_element = object_element.find(CORE_NAMESPACE + "mesh")
        if mesh_element is not None:
            vertices = [
                (float(vertex.get("x")), float(vertex.get("y")), float(vertex.get("z")))
                for vertex in mesh_element.find(CORE_NAMESPACE + "vertices")
            ]
            triangles = [
                (int(triangle.get("v1")), int(triangle.get("v2")), int(triangle.get("v3")))
                for triangle in mesh_element.find(CORE_NAMESPACE + "triangles")
            ]
            meshes[object_id] = (vertices, triangles)
        components_element = object_element.find(CORE_NAMESPACE + "components")
        if components_element is not None:
            components[object_id] = [
                (component.get("objectid"), parse_transform(component.get("transform")))
                for component in components_element
            ]
    build_items = [
        (item.get("objectid"), parse_transform(item.get("transform")))
        for item in root.find(CORE_NAMESPACE + "build")
    ]
    return unit, meshes, components, names, build_items


def collect_instances(object_id, transform, part_name, meshes, components, names, instances):
    """Walks the component tree; the part name is the nearest named SolidWorks component."""
    own_name = names.get(object_id, "")
    if own_name.startswith("MPS_") or "(" in own_name:
        part_name = own_name.split("(")[0]
    if object_id in meshes:
        instances.append((part_name, object_id, transform))
    for child_id, child_transform in components.get(object_id, []):
        collect_instances(child_id, combine(transform, child_transform), part_name, meshes, components, names, instances)


def normalize(vector):
    length = math.sqrt(vector[0] ** 2 + vector[1] ** 2 + vector[2] ** 2)
    if length < 1e-12:
        return (0.0, 0.0, 1.0)
    return (vector[0] / length, vector[1] / length, vector[2] / length)


def smooth_mesh(positions, triangles):
    """Splits vertices along sharp edges: smooth normals on curves, crisp edges on corners."""
    face_normals = []
    faces_of_vertex = [[] for _ in positions]
    for face_index, (first, second, third) in enumerate(triangles):
        point_a, point_b, point_c = positions[first], positions[second], positions[third]
        edge_one = [point_b[axis] - point_a[axis] for axis in range(3)]
        edge_two = [point_c[axis] - point_a[axis] for axis in range(3)]
        face_normals.append(normalize((
            edge_one[1] * edge_two[2] - edge_one[2] * edge_two[1],
            edge_one[2] * edge_two[0] - edge_one[0] * edge_two[2],
            edge_one[0] * edge_two[1] - edge_one[1] * edge_two[0],
        )))
        for vertex_index in (first, second, third):
            faces_of_vertex[vertex_index].append(face_index)
    crease = math.cos(math.radians(SMOOTHING_ANGLE_DEGREES))
    output_positions, output_normals, output_indices = [], [], []
    vertex_lookup = {}
    for face_index, corners in enumerate(triangles):
        own_normal = face_normals[face_index]
        for vertex_index in corners:
            summed = [0.0, 0.0, 0.0]
            for neighbour in faces_of_vertex[vertex_index]:
                neighbour_normal = face_normals[neighbour]
                if sum(own_normal[axis] * neighbour_normal[axis] for axis in range(3)) >= crease:
                    for axis in range(3):
                        summed[axis] += neighbour_normal[axis]
            normal = normalize(summed)
            key = (vertex_index, round(normal[0], 3), round(normal[1], 3), round(normal[2], 3))
            output_index = vertex_lookup.get(key)
            if output_index is None:
                output_index = len(output_positions)
                vertex_lookup[key] = output_index
                output_positions.append(positions[vertex_index])
                output_normals.append(normal)
            output_indices.append(output_index)
    return output_positions, output_normals, output_indices


def write_glb(path, primitives_by_material, materials):
    binary = bytearray()
    buffer_views, accessors, primitives = [], [], []

    def add_view(data, target):
        while len(binary) % 4:
            binary.append(0)
        buffer_views.append({"buffer": 0, "byteOffset": len(binary), "byteLength": len(data), "target": target})
        binary.extend(data)
        return len(buffer_views) - 1

    material_index = {name: index for index, name in enumerate(materials)}
    for material_name, (positions, normals, indices) in primitives_by_material.items():
        position_view = add_view(struct.pack("<%df" % (len(positions) * 3), *[c for p in positions for c in p]), 34962)
        minimum = [min(point[axis] for point in positions) for axis in range(3)]
        maximum = [max(point[axis] for point in positions) for axis in range(3)]
        accessors.append({"bufferView": position_view, "componentType": 5126, "count": len(positions), "type": "VEC3", "min": minimum, "max": maximum})
        position_accessor = len(accessors) - 1
        normal_view = add_view(struct.pack("<%df" % (len(normals) * 3), *[c for n in normals for c in n]), 34962)
        accessors.append({"bufferView": normal_view, "componentType": 5126, "count": len(normals), "type": "VEC3"})
        normal_accessor = len(accessors) - 1
        index_view = add_view(struct.pack("<%dI" % len(indices), *indices), 34963)
        accessors.append({"bufferView": index_view, "componentType": 5125, "count": len(indices), "type": "SCALAR"})
        primitives.append({
            "attributes": {"POSITION": position_accessor, "NORMAL": normal_accessor},
            "indices": len(accessors) - 1,
            "material": material_index[material_name],
        })

    gltf_materials = []
    for material_name, (base_color, metallic, roughness, emissive) in materials.items():
        material = {
            "name": material_name,
            "pbrMetallicRoughness": {
                "baseColorFactor": list(base_color) + [1.0],
                "metallicFactor": metallic,
                "roughnessFactor": roughness,
            },
        }
        if emissive:
            material["emissiveFactor"] = list(emissive)
        gltf_materials.append(material)

    document = {
        "asset": {"version": "2.0", "generator": "Panda threemf_to_glb"},
        "scene": 0,
        "scenes": [{"nodes": [0]}],
        "nodes": [{"name": "product", "mesh": 0}],
        "meshes": [{"name": "product", "primitives": primitives}],
        "materials": gltf_materials,
        "accessors": accessors,
        "bufferViews": buffer_views,
        "buffers": [{"byteLength": len(binary)}],
    }
    while len(binary) % 4:
        binary.append(0)
    json_bytes = json.dumps(document, separators=(",", ":")).encode("utf-8")
    json_bytes += b" " * ((4 - len(json_bytes) % 4) % 4)
    total_length = 12 + 8 + len(json_bytes) + 8 + len(binary)
    with open(path, "wb") as output:
        output.write(struct.pack("<III", 0x46546C67, 2, total_length))
        output.write(struct.pack("<II", len(json_bytes), 0x4E4F534A))
        output.write(json_bytes)
        output.write(struct.pack("<II", len(binary), 0x004E4942))
        output.write(binary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input_3mf")
    parser.add_argument("output_glb")
    parser.add_argument("--skip", nargs="*", default=[], help="part names to leave out (e.g. a loose spare part)")
    arguments = parser.parse_args()

    unit, meshes, components, names, build_items = read_model(arguments.input_3mf)
    scale_to_metres = {"millimeter": 0.001, "centimeter": 0.01, "meter": 1.0, "inch": 0.0254}[unit]

    instances = []
    for object_id, transform in build_items:
        collect_instances(object_id, transform, names.get(object_id, ""), meshes, components, names, instances)
    instances = [instance for instance in instances if not any(skip in instance[0] for skip in arguments.skip)]

    # world positions in 3MF space (Z up, millimetres)
    placed = []
    for part_name, mesh_id, transform in instances:
        vertices, triangles = meshes[mesh_id]
        placed.append((part_name, [apply_transform(transform, vertex) for vertex in vertices], triangles))
    all_points = [point for _, points, _ in placed for point in points]
    centre_x = (min(p[0] for p in all_points) + max(p[0] for p in all_points)) / 2
    centre_y = (min(p[1] for p in all_points) + max(p[1] for p in all_points)) / 2
    floor_z = min(p[2] for p in all_points)

    primitives_by_material = {}
    materials = {}
    part_report = {}
    for part_name, points, triangles in placed:
        material_name, base_color, metallic, roughness, emissive = material_for(part_name)
        materials[material_name] = (base_color, metallic, roughness, emissive)
        part_report[part_name] = material_name
        # glTF: metres, Y up  ->  (x, z, -y) from the 3MF Z-up frame
        converted = [
            ((x - centre_x) * scale_to_metres, (z - floor_z) * scale_to_metres, -(y - centre_y) * scale_to_metres)
            for x, y, z in points
        ]
        positions, normals, indices = smooth_mesh(converted, triangles)
        bucket = primitives_by_material.setdefault(material_name, ([], [], []))
        offset = len(bucket[0])
        bucket[0].extend(positions)
        bucket[1].extend(normals)
        bucket[2].extend(index + offset for index in indices)

    write_glb(arguments.output_glb, primitives_by_material, materials)
    size_x = (max(p[0] for p in all_points) - min(p[0] for p in all_points))
    size_y = (max(p[1] for p in all_points) - min(p[1] for p in all_points))
    size_z = (max(p[2] for p in all_points) - min(p[2] for p in all_points))
    print("parts:", len(part_report), "instances:", len(instances))
    for part_name in sorted(part_report):
        print("  %-40s %s" % (part_name, part_report[part_name]))
    print("size mm: %.1f x %.1f x %.1f" % (size_x, size_y, size_z))
    print("triangles:", sum(len(indices) // 3 for _, _, indices in primitives_by_material.values()))


if __name__ == "__main__":
    sys.exit(main())
