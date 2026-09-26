const fs = require('fs');
const src = fs.readFileSync('D:/dev/dywatch/shots/uid.xml', 'utf8');
const texts = [];
const re = /text="([^"]*)"/g;
let m;
while ((m = re.exec(src)) !== null) {
  if (m[1] && m[1].trim()) texts.push(m[1].trim());
}
const ids = [];
const re2 = /resource-id="([^"]*)"/g;
while ((m = re2.exec(src)) !== null) {
  if (m[1] && !ids.includes(m[1])) ids.push(m[1]);
}
console.log('TEXTS:');
texts.forEach(t => console.log('  ' + t));
console.log('VISIBLE IDS (hint/header/about):');
ids.filter(i => /hint|page_name|about|back/.test(i)).forEach(i => console.log('  ' + i));
