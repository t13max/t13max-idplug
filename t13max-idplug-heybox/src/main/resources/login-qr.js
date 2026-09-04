/* 只复制官方登录组件的二维码图像，不解码、不发送到其他服务。 */
(() => {
    if (location.origin !== 'https://www.xiaoheihe.cn') return {state: 'unavailable'};
    if (document.querySelector('nav img[alt$="头像"]')) return {state: 'signedIn'};
    /** 判断元素是否在页面布局中可见。 */
    function visible(element) { const rect = element.getBoundingClientRect(); return rect.width > 0 && rect.height > 0 && getComputedStyle(element).visibility !== 'hidden' && getComputedStyle(element).display !== 'none'; }
    const labels = Array.from(document.querySelectorAll('div, span, p')).filter(element => element.children.length === 0 && /^(小黑盒扫码登录|扫码快捷登录)$/.test(element.innerText.trim()) && visible(element));
    for (const label of labels) {
        let region = label.parentElement;
        for (let depth = 0; region && depth < 4; depth++, region = region.parentElement) {
            if (region === document.body || region.querySelector('main')) break;
            if (/已过期|已失效|二维码失效|二维码过期/.test(region.innerText)) return {state: 'expired'};
            if (/安全验证|完成验证|滑动验证/.test(region.innerText)) return {state: 'verification'};
            const images = Array.from(region.querySelectorAll('canvas, img')).filter(element => { const rect = element.getBoundingClientRect(); return visible(element) && rect.width >= 100 && rect.width <= 400 && Math.abs(rect.width - rect.height) <= 8; });
            if (images.length !== 1) continue;
            const source = images[0];
            const width = source.tagName === 'CANVAS' ? source.width : source.naturalWidth;
            const height = source.tagName === 'CANVAS' ? source.height : source.naturalHeight;
            if (width < 64 || height < 64 || width > 1024 || height > 1024) return {state: 'unavailable'};
            try {
                const canvas = document.createElement('canvas');
                canvas.width = width;
                canvas.height = height;
                canvas.getContext('2d').drawImage(source, 0, 0);
                const image = canvas.toDataURL('image/png');
                return image.length <= 400000 ? {state: 'ready', image} : {state: 'unavailable'};
            } catch (error) { return {state: 'unavailable'}; }
        }
    }
    return {state: 'unavailable'};
})();
