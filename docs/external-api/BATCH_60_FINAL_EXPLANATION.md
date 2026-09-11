# BATCH 60 — WRMS EXTERNAL API (SARAL HINGLISH EXPLANATION FOR RAJAT)

**Namaste Rajat!**  
Is document me Batch 60 ke dauran implement kiye gaye **External API aur Integration Layer** ko bilkul aasan Hinglish bhasha me samjhaya gaya hai taaki aapko complete internal picture clear rahe.

---

### A. API kya hoti hai?
Jaise aap ek restaurant me jaate hain, to aap direct kitchen me jakar khana nahi banate; aap **Waiter** ko apna order dete hain, waiter kitchen se khana lakar aapki table par rakh deta hai.  
Software ki duniya me **API wahi waiter hai**. Jab koi doosra software (jaise HR portal ya attendance machine) WRMS se data maangta hai, to wo direct database me nahi ghusta, balki WRMS ki **API** ko request bhejta hai, aur API safe tarike se data lakar use de deti hai.

---

### B. WRMS ka external API kya karega?
Third-party applications ko WRMS ka live data provide karega:
1. **Rosters**: Kaunsa employee kis din kis shift me hai (Morning, General, Evening, Night ya Weekly Off).
2. **Employees**: Active staff ki basic details (naam, code, email).
3. **Shifts**: Available shifts aur unki timing/capacity.
4. **Leaves**: Approved chhuttiyon ka record.

---

### C. Third party ko kya dena hai?
Third party company/developer ko sirf 3 cheezein deni hain:
1. **Base URL**: e.g., `https://<YOUR-RAILWAY-DOMAIN>.up.railway.app`
2. **API Key**: Ek secret key, jaise `wrms_live_a1b2c3d4e5f6...`
3. **Documentation & Postman Collection**: Hamari banayi hui `docs/external-api` guide.

---

### D. API key/token kya hota hai?
API key ek **secret password** jaisi hoti hai jo insan ke liye nahi, balki machine ke liye hoti hai. Jab third party software call karega, to wo request ke header me ye key lagayega (`X-API-Key: wrms_live_...`). Isse WRMS pehchaan leta hai ki ye request kis partner ki hai.

---

### E. Authentication kaise hoti hai?
Authentication ka matlab: **"Tum kaun ho?"**  
Jab request WRMS par aati hai, to `ApiKeyAuthenticationFilter` us API key ko leta hai, uska **SHA-256 hash** banata hai, aur check karta hai ki kya ye key database me active registered client se match karti hai. Agar match hui to access allow hota hai; agar galat hui to turant `401 Unauthorized` bhej diya jata hai.

---

### F. Authorization kaise hoti hai?
Authorization ka matlab: **"Tumhe kya dekhne ki permission hai?"**  
Maan lijiye kisi partner ko sirf Employee list dekhne ka permission diya hai. Agar wo Roster endpoint call karega, to WRMS use block kar dega aur `403 Forbidden` error bhejega.

---

### G. Scope kya hai?
Scope permission ka naam hai:
* `ROSTER_READ`: Roster dekhne ka scope.
* `EMPLOYEE_READ`: Employee list dekhne ka scope.
* `SHIFT_READ`: Shifts dekhne ka scope.
* `LEAVE_READ`: Approved leaves dekhne ka scope.

---

### H. Request WRMS ke andar kaise travel karti hai?
1. Third party application se HTTPS request aati hai.
2. `CorrelationIdFilter` request ko ek unique **X-Request-ID** (UUID) deta hai.
3. `RateLimitFilter` check karta hai ki client 1 minute me 60 se zyada request to nahi bhej raha.
4. `ApiKeyAuthenticationFilter` key ko verify karta hai.
5. Spring Security permission/scope check karti hai.
6. `ExternalRosterController` request receive karta hai.
7. `ExternalApiService` database repository ko call karta hai.
8. Data DTO me convert hota hai aur clean JSON bankar third party tak pahunch jata hai.

---

### I. Database se data kaise aata hai?
Data MySQL database ki existing consolidated tables se aata hai. Batch 56 aur 59 me humne N+1 query issue solve kiya tha, isliye yahan bhi `JOIN FETCH` queries use hoti hain, jisse database par bilkul load nahi padta aur ek hi single roundtrip me pura data nikal aata hai.

---

### J. DTO kya karta hai?
DTO ka full form hai **Data Transfer Object**.  
Database ki entity me internal fields hote hain jaise passwords, user IDs, security tokens. **DTO ek filter ki tarah kaam karta hai**. Hum DTO ke zariye sirf wahi fields bahar bhejte hain jo third party ke kaam ki hain. Passwords aur internal IDs kabhi bahar nahi jaate!

---

### K. Response third party tak kaise pahunchta hai?
Response ek standard JSON format me HTTP protocol ke through third party software tak pahunchta hai:
```json
{
  "success": true,
  "data": { ... },
  "error": null,
  "meta": {
    "timestamp": "2026-09-11T19:00:00",
    "requestId": "uuid-here",
    "version": "v1"
  }
}
```

---

### L. Swagger kya hai?
**Swagger UI** ek ready-made web page hai (`/swagger-ui.html`). Third-party developers bina koi code likhe browser me hi saare endpoints ko dekh sakte hain, unka format samajh sakte hain aur "Try it out" button dabakar test bhi kar sakte hain.

---

### M. Postman kya hai?
**Postman** ek testing software hai jisme developers API requests save karke run karte hain. Humne `WRMS_External_API_v1.postman_collection.json` file bana di hai jise import karke third party ek click me test kar sakti hai.

---

### N. Versioning kyu hai?
Endpoints ke URL me `/v1/` lagaya gaya hai (`/api/external/v1/...`).  
Kyu? Maan lijiye 1 saal baad aapko API ka structure badalna pada. To aap `/v2/` bana sakte hain bina purane `/v1/` walo ko tode. Purane clients bina rukawat kaam karte rahenge!

---

### O. Security kaise maintain ho rahi hai?
1. **Raw Key Kabhi Save Nahi Hoti**: Database me sirf SHA-256 hash save hota hai. Agar koi database chura bhi le, to bhi API key pata nahi laga sakta.
2. **Dedicated API Keys**: Third party ko Admin ya Employee password dene ki koi zaroorat nahi.
3. **Instant Revocation**: Agar koi key leak ho jaye to Admin dashboard se ek click me disable ya delete kar sakte hain.
4. **Rate Limiting**: Har client ko per minute limit (default 60 req/min) me rakha gaya hai taaki server hang na ho.

---

### P. Agar API fail ho jaye to kaise troubleshoot karenge?
Har response ke `meta` object me ek unique `requestId` hota hai (jaise `req-12345`).  
Agar third party kahe ki unka request fail hua, to unse `requestId` maang lijiye. Aap Railway ke logs me wo `requestId` search karenge to aapko exact error aur line number turant mil jayega!

---

### Q. Railway par kya configure karna hoga?
Zero mandatory setup! Sab kuch code me default configuration ke sath configured hai.  
Lekin agar aap chahein to Railway dashboard me ek environment variable daal sakte hain:
* `WRMS_EXTERNAL_API_KEY`: Aapki custom secret default key (optional).

---

### R. Future me v2 API kaise banegi?
Agar future me naye features ya breaking changes aate hain, to naya controller package banega:  
`com.weeklyroster.controller.external.v2`  
Aur URL hoga: `/api/external/v2/...`.  
Existing `/api/external/v1/...` waise hi chalte rahega.

---

### S. Third party ko kya share karna safe hai?
- Base URL (e.g. Railway link)
- API Key (sirf unki apni)
- Allowed Scopes
- Swagger UI link (`/swagger-ui.html`)
- Postman collection
- Documentation guides

---

### T. Kya KABHI share nahi karna hai?
> [!CAUTION]
> In cheezon ko kisi third party ke sath KABHI share nahi karna hai:
> 1. Admin login username & password
> 2. Employee login usernames & passwords
> 3. Railway account login credentials
> 4. MySQL database host, username, ya password
> 5. Brevo API key
> 6. Gmail SMTP password
> 7. JWT Secret key
