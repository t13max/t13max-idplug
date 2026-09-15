const {test} = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/reader.js'), 'utf8');

/** 使用模拟 DOM 运行生产提取脚本，不访问网络。 */
function extract(url, singles = {}, multiples = {}) {
    const document = {querySelector: selector => singles[selector] || null, querySelectorAll: selector => multiples[selector] || []};
    return JSON.parse(JSON.stringify(vm.runInNewContext(script, {document, location: {href: url}, URL})));
}

/** 创建模拟可见文本节点。 */
function node(text, href = '', title = text) { return {innerText: text, getAttribute: () => href, querySelector: selector => selector === '.bbs-content__title' && title ? {innerText: title} : null}; }

/** 模拟主评论与楼中楼的节点类型和稳定标识。 */
function comment(text, child, id = '', rootId = id) { return {innerText: text, matches: selector => child && selector === '.children-item__comment-content', closest: selector => ({getAttribute: () => selector === '.link-comment__comment-item' ? rootId : id})}; }

/** 验证主页条目去重和外链过滤。 */
test('home extracts posts, login marker and communities', () => {
    const result = extract('https://www.xiaoheihe.cn/app/bbs/home', {'nav img[alt$="头像"]': node('avatar')}, {'main a[href]': [node('Post A', '/app/bbs/link/123'), node('Duplicate', '/app/bbs/link/123'), node('External', 'https://evil.test/app/bbs/link/123'), node('Profile', '/app/user/profile/1')], 'main button': [node('Steam'), node('搜索'), node('Steam')]});
    assert.equal(result.signedIn, true);
    assert.equal(result.items.length, 1);
    assert.equal(result.items[0].url, 'https://www.xiaoheihe.cn/app/bbs/link/123');
    assert.deepEqual(result.communities, ['Steam']);
});

/** 验证标题、正文段落和子评论顺序。 */
test('detail extracts body and comments without form contents', () => {
    const result = extract('https://www.xiaoheihe.cn/app/bbs/link/123', {'.section-title__content': node('Title'), '.image-text__content': node('First\n\nSecond 😀')}, {'.comment-item__content, .children-item__comment-content': [comment('Comment', false, '10'), comment('Reply', true, '12', '10'), comment('Reply without ID', true)]});
    assert.deepEqual(result.items.map(item => item.kind), ['Title', 'Body', 'Body', 'Comment', 'Reply', 'Reply']);
    assert.equal(result.items[2].text, 'Second 😀');
    assert.equal(result.signedIn, false);
});

/** 验证外部站点不能产生桥接内容。 */
test('external origin is rejected', () => { assert.equal(extract('https://evil.test/app/bbs/home'), null); });

/** 未登录只有在出现明确登录按钮时才成立。 */
test('requires explicit sign-in button for signed-out state', () => {
    assert.equal(extract('https://www.xiaoheihe.cn/app/bbs/home').signedOut, false);
    assert.equal(extract('https://www.xiaoheihe.cn/app/bbs/home', {}, {'nav button': [node('登录')]}).signedOut, true);
});

/** 列表只显示标题，不带昵称、等级和正文摘要。 */
test('post list excludes author and level', () => {
    const result = extract('https://www.xiaoheihe.cn/app/bbs/home', {}, {'main a[href]': [node('Alice Lv.99 Title Summary 123', '/app/bbs/link/1', 'Title'), node('Missing title', '/app/bbs/link/2', '')]});
    assert.equal(result.items.length, 1);
    assert.equal(result.items[0].text, 'Title');
});

/** 验证页面条目和单条内容都有限制。 */
test('bounds item count and text size', () => {
    const anchors = Array.from({length: 250}, (_, i) => node('x'.repeat(15000), '/app/bbs/link/' + i));
    const result = extract('https://www.xiaoheihe.cn/app/bbs/home', {}, {'main a[href]': anchors});
    assert.equal(result.items.length, 200);
    assert.equal(result.items[0].text.length, 12000);
});
