const fs = require('fs');
const path = require('path');

try {
  const accessToken = fs.readFileSync(path.join('./', 'accesstoken'));

  if (!accessToken) {
    process.exit(1);
  }

  // eslint-disable-next-line no-new-wrappers
  const fileContents = `{ "accessToken": "${new String(accessToken).trim()}" }`;
  fs.writeFileSync(path.join('./', 'env.json'), fileContents);
} catch (error) {
  // 文件不存在或其他读取错误
  console.error('Failed to read access token file:', error.message);
  process.exit(1);
}
