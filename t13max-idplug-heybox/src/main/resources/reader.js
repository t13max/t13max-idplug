/* 只提取官方网页已经渲染的阅读内容，不访问凭据或私有接口。 */
(() => {
    /** 将网页文字压缩成有界单行。 */
    function clean(value) { return String(value || '').replace(/\s+/g, ' ').trim().slice(0, 12000); }
    /** 读取元素的可见文本。 */
    function text(element) { return clean(element?.innerText); }
    const url = new URL(location.href);
    if (url.origin !== 'https://www.xiaoheihe.cn') return null;
    const signedIn = !!document.querySelector('nav img[alt$="头像"]');
    const signedOut = !signedIn && Array.from(document.querySelectorAll('nav button')).some(element => text(element) === '登录');
    const items = [];
    const communities = [];
    if (/^\/app\/bbs\/link\/\d+$/.test(url.pathname)) {
        const title = text(document.querySelector('.section-title__content'));
        if (title) items.push({text: title, url: '', kind: 'Title'});
        const body = document.querySelector('.image-text__content');
        for (const line of (body?.innerText || '').split(/\n+/)) {
            if (clean(line)) items.push({text: clean(line), url: '', kind: 'Body'});
        }
        for (const node of document.querySelectorAll('.comment-item__content, .children-item__comment-content')) {
            if (text(node)) items.push({text: text(node), url: '', kind: node.matches('.children-item__comment-content') ? 'Reply' : 'Comment'});
        }
    } else if (/^\/app\/(bbs\/home|topic\/link\/\d+)$/.test(url.pathname)) {
        const seen = new Set();
        for (const anchor of document.querySelectorAll('main a[href]')) {
            const target = new URL(anchor.getAttribute('href'), location.href);
            if (target.origin !== url.origin || !/^\/app\/bbs\/link\/\d+$/.test(target.pathname) || seen.has(target.pathname) || !text(anchor)) continue;
            seen.add(target.pathname);
            const title = text(anchor.querySelector('.bbs-content__title'));
            if (title) items.push({text: title, url: target.href, kind: 'Post'});
        }
        if (url.pathname === '/app/bbs/home') {
            for (const button of document.querySelectorAll('main button')) {
                const label = text(button);
                if (label && !['搜索', '查看', '查看全部'].includes(label) && label.length <= 60 && !communities.includes(label)) communities.push(label);
            }
        }
    }
    return {url: url.origin + url.pathname, signedIn, signedOut, items: items.slice(0, 200), communities: communities.slice(0, 40)};
})();
