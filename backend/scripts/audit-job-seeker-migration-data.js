const database = db.getSiblingDB('waypoint');

function matchingPaths(value, prefix = '', matches = new Set()) {
  if (value == null) return matches;
  if (typeof value === 'string') {
    if (/employee/i.test(value)) matches.add(prefix);
    return matches;
  }
  if (Array.isArray(value)) {
    value.forEach((item) => matchingPaths(item, `${prefix}[]`, matches));
    return matches;
  }
  if (typeof value === 'object') {
    for (const [key, nestedValue] of Object.entries(value)) {
      const path = prefix ? `${prefix}.${key}` : key;
      if (/employee/i.test(key)) matches.add(`${path} (field name)`);
      matchingPaths(nestedValue, path, matches);
    }
  }
  return matches;
}

for (const { name } of database.getCollectionInfos()) {
  let matches = 0;
  const paths = new Set();
  for (const document of database.getCollection(name).find({}).toArray()) {
    if (/employee/i.test(EJSON.stringify(document))) {
      matches += 1;
      matchingPaths(document, '', paths);
    }
  }
  if (matches > 0 || /employee/i.test(name)) {
    print(`${name}: ${matches} document(s) containing employee terminology`);
    print(`  paths: ${[...paths].sort().join(', ')}`);
  }
}
