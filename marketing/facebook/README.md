# Facebook page

The page's profile picture (the "90s Kid Arcade" lettering) and cover (the
link card's style, with game title screens fetched from libretro's
thumbnails when rendered; none kept in the repo). Render them with the
pre-installed Chromium:

    node -e "const {chromium}=require('playwright');(async()=>{const b=await chromium.launch();for(const [f,w,h] of [['logo',1080,1080],['cover',1640,624]]){const p=await b.newPage({viewport:{width:w,height:h}});await p.goto('file://'+process.cwd()+'/'+f+'.html');await p.waitForTimeout(2500);await p.screenshot({path:f+'.png'})}await b.close()})()"

Sizes: Facebook shows a Page cover at 820 × 312 on computers and 640 × 360
on phones (the sides cut off). The cover is drawn at twice that, 1640 × 624,
for sharp screens. The Facebook app on phones shows only about the middle
900 px and puts the round profile picture over the bottom middle, so the
words sit centred in the top half. Upload it as PNG: Facebook blurs text in
a JPG cover. The old title screens are drawn in sharp pixels
(`image-rendering: pixelated`), not smoothed.
The page text (name, bio, details, first post) is in facebook-page.md.
No money asks anywhere (FBNeo's license): no donations, no paid promotion of
anything sold.
