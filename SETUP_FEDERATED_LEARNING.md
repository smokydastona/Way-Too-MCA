# Federated Learning Setup - Simple Guide

## What You're Setting Up
A free cloud API that lets all Minecraft servers share what their mobs learn. Takes 10 minutes.

---

## Step 1: Create Cloudflare Account (2 minutes)
1. Go to https://dash.cloudflare.com/sign-up
2. Use your email, create password
3. Verify email
4. **You don't need a domain** - we'll use the free workers.dev subdomain

---

## Step 2: Install Node.js (if not installed)
1. Go to https://nodejs.org/
2. Download LTS version (green button)
3. Install with default settings
4. Restart your terminal

---

## Step 3: Install Cloudflare CLI (1 minute)
Open PowerShell and run:
```powershell
npm install -g wrangler
```

Wait for it to finish installing.

---

## Step 4: Login to Cloudflare (1 minute)
```powershell
wrangler login
```
- Browser opens automatically
- Click "Allow" to authorize
- Close browser tab when it says "Success"

---

## Step 5: Create Storage Space (1 minute)
```powershell
cd "C:\Users\smoky\OneDrive\Desktop\2 lost cities\Minecraft-GAN-City-Generator\cloudflare-worker"
wrangler kv:namespace create TACTICS_KV
```

**IMPORTANT:** Copy the ID from the output. It looks like:
```
{ binding = "TACTICS_KV", id = "abc123..." }
```

**Copy the `abc123...` part!**

---

## Step 6: Configure the Worker (2 minutes)
1. Open `cloudflare-worker/wrangler.toml` in Notepad
2. Find this line:
   ```toml
   id = "YOUR_KV_NAMESPACE_ID"
   ```
3. Replace `YOUR_KV_NAMESPACE_ID` with your ID from Step 5
4. Save and close

---

## Step 7: Deploy (2 minutes)
```powershell
npm install
npm run deploy
```

Wait for deployment to finish. You'll see:
```
Published mca-ai-tactics-api
  https://mca-ai-tactics-api.YOUR-NAME.workers.dev
```

**COPY THIS URL!** This is your API endpoint.

---

## Step 8: Test It Works (1 minute)
```powershell
curl https://mca-ai-tactics-api.YOUR-NAME.workers.dev/api
```

Should return:
```json
{"status":"ok","message":"MCA AI Federated Learning API"}
```

✅ If you see this, IT WORKS!

---

## Step 9: Enable in Mod (30 seconds)
I'll do this part - just tell me your Worker URL from Step 7!

---

## Troubleshooting

### "wrangler: command not found"
- Restart PowerShell after installing Node.js
- Run: `npm install -g wrangler` again

### "KV namespace not found"
- Make sure you copied the ID correctly in Step 6
- Check for typos in `wrangler.toml`

### "Deployment failed"
- Run: `wrangler logout` then `wrangler login` again
- Try deploying again: `npm run deploy`

---

## What Happens Next

Once enabled:
1. Your server's mobs learn tactics normally
2. Every 5 minutes, best tactics upload to Cloudflare
3. Every 10 minutes, your server downloads global best tactics
4. Your mobs now know what ALL servers' mobs learned!

Example:
- Someone's elite zombie discovers "retreat at 20% health" is super effective
- 5 minutes later, it uploads to your Cloudflare Worker
- 10 minutes later, YOUR zombies download it and start using it
- Your zombies instantly get smarter without learning it themselves!

---

## Cost
**FREE!** Cloudflare Workers free tier includes:
- 100,000 requests/day (way more than needed)
- 1 GB storage (tactics are tiny, you'll use <1 MB)
- Global CDN (fast everywhere)

You won't hit the limits even with 100+ servers using it.

---

## Ready?
Start with Step 1! Let me know when you have your Worker URL from Step 7.
