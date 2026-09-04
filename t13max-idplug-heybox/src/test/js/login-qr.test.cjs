const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/login-qr.js'), 'utf8');

/** 使用模拟扫码区域验证生产脚本，不读取真实令牌。 */
function extract({labelText = '小黑盒扫码登录', regionText = '小黑盒扫码登录', images = 1, tainted = false, signedIn = false, origin = 'https://www.xiaoheihe.cn'} = {}) {
    const image = {tagName: 'IMG', naturalWidth: 128, naturalHeight: 128, getBoundingClientRect: () => ({width: 128, height: 128})};
    const region = {innerText: regionText, parentElement: null, querySelector: () => null, querySelectorAll: () => Array.from({length: images}, () => image)};
    const label = {children: [], innerText: labelText, parentElement: region, getBoundingClientRect: () => ({width: 120, height: 20})};
    const document = {body: {}, querySelector: () => signedIn ? {} : null, querySelectorAll: () => [label], createElement: () => ({getContext: () => ({drawImage: () => { if (tainted) throw new Error('tainted'); }}), toDataURL: () => 'data:image/png;base64,TEST'})};
    return JSON.parse(JSON.stringify(vm.runInNewContext(script, {document, location: {origin}, getComputedStyle: () => ({visibility: 'visible', display: 'block'})})));
}

/** 只接受明确官方扫码提示附近的唯一图像。 */
test('extracts login QR only', () => { assert.equal(extract().state, 'ready'); assert.equal(extract({labelText: '立即下载小黑盒APP'}).state, 'unavailable'); });

/** 过期与额外验证不能继续显示旧二维码。 */
test('reports expired and verification states', () => { assert.equal(extract({regionText: '二维码已过期'}).state, 'expired'); assert.equal(extract({regionText: '请完成验证'}).state, 'verification'); });

/** 图像不唯一或不可复制时明确失败。 */
test('fails closed on ambiguous or tainted image', () => { assert.equal(extract({images: 2}).state, 'unavailable'); assert.equal(extract({tainted: true}).state, 'unavailable'); });

/** 非官方源被拒绝，已登录时不再提取二维码。 */
test('checks origin and signed in state', () => { assert.equal(extract({origin: 'https://evil.test'}).state, 'unavailable'); assert.equal(extract({signedIn: true}).state, 'signedIn'); });
