# Facebook page

The page's profile picture and cover, as HTML drawn only from our own shapes
(no game pictures: SNK and Capcom art stays out of the repo and off our own
banners). Render them with the pre-installed Chromium:

    node -e "const {chromium}=require('playwright');(async()=>{const b=await chromium.launch();for(const [f,w,h] of [['logo',1080,1080],['cover',1640,624]]){const p=await b.newPage({viewport:{width:w,height:h}});await p.goto('file://'+process.cwd()+'/'+f+'.html');await p.waitForTimeout(2500);await p.screenshot({path:f+'.png'})}await b.close()})()"

Keep the cover's words inside the middle 1,100 px: phones show only that.
The page text (name, bio, details, first post) is in facebook-page.md.
No money asks anywhere (FBNeo's license): no donations, no paid promotion of
anything sold.
