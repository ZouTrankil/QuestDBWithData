-- 只读这59个已裁定的原报价窗口，不推进原长区间失败任务。
-- 原量为零的报价保持；当前代码和名称不证明历史发行人连续。
SELECT p.trade_date, p.symbol, p.provider, p.venue,
       p.open, p.high, p.low, p.close, p.volume,
       p.adj_open, p.adj_high, p.adj_low, p.adj_close, p.adj_volume,
       p.dividend, p.split_factor, p.currency, p.raw_price_basis,
       p.source_sha256, p.observed_at,
       p.volume > 0 AS source_positive_volume,
       'OTC供应商原报价；当前代码不证明逐日发行人身份；零量不证明每日成交' AS source_scope
FROM us_equity_daily p
WHERE p.provider='YAHOO'
  AND p.symbol IN ('ABTZY','ADERY','ADVT','AGDY','AIPUF','AJYG','AKRYY','ALEDY','AMCCF','AMWK','ANDI','AONNF','APCOF','ASTAQ','AUTR','AVNE','AWAW','BETSF','BOYAF','BRCOQ','BRZV','BSNLF','BXXRF','CAMG','CBHC','CBLO','CBLRF','CCLX','CEBUF','CGUIF','CHCJY','CHIT','CHNFF','CHNGQ','CHOOF','CHYCF','CMNR','CNCM','CNDL','CPADF','CRHKF','CRXPF','DBLR','DGDCF','DKAM','DKTS','DRKOF','DVLP','EDCFF','EDSFF','EGDFF','EGMCF','EGRNF','EHOS','ELALF','ELCR','ELRA','ENFY','ENTI')
  AND p.trade_date>='2011-10-16' AND p.trade_date<='2026-10-16'
  AND (
  (p.symbol='ABTZY' AND p.source_sha256='451b210d0ca1478c4e4cddbfca497c6b3943fbd59668917a4fb2543e1afdbdd9' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='ADERY' AND p.source_sha256='1b6af9ca7f9b6b4bed22f905600c02265a7f9407fc581d2986dbc0526fd9e84d' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='ADVT' AND p.source_sha256='334d7341ae0caab367e97f525be59a4fbad94c3bf2ae1f83aa8ed4640960f3de' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='AGDY' AND p.source_sha256='0551d6f43af20ceb301356d6585b70e9e8d8939f687d5bfbb6f7529367c56eba' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='AIPUF' AND p.source_sha256='6e5c3446d6d05a033caaa7f13013ac635026cfd821f871eed5c48960cabd50fd' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='AJYG' AND p.source_sha256='eb5ebd35a6e4d6e902d59ce9af38d201c35d9fc3b62c25a7da872e88ac25e2af' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='AKRYY' AND p.source_sha256='564fefb7190c2cb862c69f2fd96efac906bb276ac50d613a274e70553c7794f0' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='ALEDY' AND p.source_sha256='74f81f1fd857ef61dea967933d622603992c837e39783a77b00f2d00184d96af' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='AMCCF' AND p.source_sha256='98079e7f799a046440d080748264fa561249392b5bac48402039c6c9bdf28047' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='AMWK' AND p.source_sha256='54ecb0e24c2543237740409c3bd8d2245a39372d9b0940eb6293b7dcccaf6f4e' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='ANDI' AND p.source_sha256='5291db9278e403a744db5ad561d8836018539e104c2505776999d6ea287a56e1' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='AONNF' AND p.source_sha256='c31ea514bfdba5c6fcf16a05b70bd2178e638badff9888609b73564eb8374719' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='APCOF' AND p.source_sha256='fc7c707bcd7bfda7418c4cc3b809335d0136c36540de4c58f64fbc7af6001d62' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='ASTAQ' AND p.source_sha256='8d10180df7fac1653e55b8019b35995d72c1d0016849f48aaa2e87b777a2e380' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='AUTR' AND p.source_sha256='3956da91539fc3517e4cac2d07bcf492af449f971d6db6e22ac0f7f2dda3bc0e' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='AVNE' AND p.source_sha256='b0faa9c0c7ccc55ddb21d9310b6d766fd79dd79d6e4ef5eb9355029be979e8b0' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='AWAW' AND p.source_sha256='f750c7fb4ca14b1941f21612ad8a7d6d9435ad4aab5e31a311ba5c46e46f301e' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='BETSF' AND p.source_sha256='4a5b8a7ceb0f28443dfd74eae83326c0483e93030259051219412c8df67b6e49' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='BOYAF' AND p.source_sha256='dd6b48e635a72f5b54b6b7dadf48798c5549160338663d7e5526ab04d71bb4e1' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='BRCOQ' AND p.source_sha256='228741a7e39ec71741769ef65f869d4feafe8e8a3e56241270fcfe002b34f0b1' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='BRZV' AND p.source_sha256='d23d36a92f085b04d6f2fb161cc5be073d96f445393f0b7a37526425e93f2e01' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='BSNLF' AND p.source_sha256='d20ae9a3d3d21c147f14886a350f6717868b8dfa65e396af07750515827580b5' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='BXXRF' AND p.source_sha256='82ca2341f7487032f87fa56b41d2e909c7275d41d2c383be81fd4afc4a1f2c74' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CAMG' AND p.source_sha256='becfa468fecc2255f687fc5b709ccccb192cd67b4268db7fd8cb1b5a6727fe83' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='CBHC' AND p.source_sha256='8381d4de004d149519d909df6957539f93e921de0289f11cfd16f6966a36651f' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='CBLO' AND p.source_sha256='5fa8f053f08c8761861898b3553b73d3623c886ce98b6edcd6f6e86e00aca2ff' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CBLRF' AND p.source_sha256='daf82624da121124781f8896946a0ea25c98017ce3d70aa3a3882b0c7e56219e' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CCLX' AND p.source_sha256='716e414b1183aaef56acc06373e96165c7dd645cefad710f520d3aba5f2a834c' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='CEBUF' AND p.source_sha256='38d62dfb5dfd67d27f588e852d3a20e462102953eb68fad94e8d4b665e63d0da' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CGUIF' AND p.source_sha256='29969a1b2d31a99ea7fd64a2e64e4177fbb10d4c25eceb8fa0816640076bfb32' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CHCJY' AND p.source_sha256='71c12f88260ec7f323f1dc2913baa4fa3f581468bd333664819803dbe6369524' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='CHIT' AND p.source_sha256='2409d4e35a87c0d0777f4deb6ceaa48959433c4524fd18a49260cb5da4a90e32' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CHNFF' AND p.source_sha256='4f67a3d605f4bcb021fc8cefb197ad17a16afac634577591b7d5efd860c80531' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CHNGQ' AND p.source_sha256='c65d5e9cb9439e1c6196a67f8d972843ff1d67bfd49b6ea5047e2e1d3a3df34c' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CHOOF' AND p.source_sha256='0cf2353c1589b48ff4c47b11b22c05e0a0b6d4482aff489fcb33c94d382fa639' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CHYCF' AND p.source_sha256='d2efd2c877c59062850cfe1e8a3a53e21e894e978cef13d815fe3fe9ef204160' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='CMNR' AND p.source_sha256='9ece1ca1ea695fec7839b43846fb63a6830c8fe93c46c7bf8a7799c28748a61f' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='CNCM' AND p.source_sha256='8de74b5a7584d9968971b6dc4f93dd335fbe592f3c089c22f8a7749823513d32' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='CNDL' AND p.source_sha256='38d0dab5662369493f63ca6a85ef84ed53e95e8243e7a16256908fad5716478a' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='CPADF' AND p.source_sha256='c6a81ddeb64f2bdbdaebb2daad75656d6803662a987e1daaab0cb2854a579079' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='CRHKF' AND p.source_sha256='371f353a4c9e9b3d8013cf4f9503cd3e6bf74c65bdb7e463f0976a306ad28679' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='CRXPF' AND p.source_sha256='1c3edd5076723c7fdc3d942f31a93ea1baff2c80b3320a20af4483a6023365ba' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='DBLR' AND p.source_sha256='19ecc094ff99618786fb10a5ebfbe979417a5d2e28ff61fac0b064e9d199614a' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='DGDCF' AND p.source_sha256='98e501794f96ed0433a3d350577070f6ec505414bd0070320ae0dfa57dbd4194' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='DKAM' AND p.source_sha256='1f9dcf341850a280004a486b0b1e015b6803bd7248bd9c120ff9824943a5da2b' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='DKTS' AND p.source_sha256='818f3f54aad4093fd81ac1c84037140235464613edc3085d8702f3c530db3c4d' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='DRKOF' AND p.source_sha256='fd46932d98d67690256767a6f529b226a030987914390eac405c220c27b565f6' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='DVLP' AND p.source_sha256='58e31f874ebcb9e5c4807ecc1706bb99d0c7fc09a3807fba9a51f3df4284645e' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='EDCFF' AND p.source_sha256='c5cb21db4f31054abd336f95f207fd26415c525452eb46344afe277e0bd47a05' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='EDSFF' AND p.source_sha256='9b027a8ff4008b24383abaf9c0c0fdde461193b1f018e6fa0ce7174647f1d0c7' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='EGDFF' AND p.source_sha256='9155e71ecaa760f6e43cdd5b9fb55c8796b8f7e4c8a4ae9eb4a7b32028a67396' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='EGMCF' AND p.source_sha256='f9f386b2d6b5a1e277ca01248a75c7c320a6e57166b4ca7c0457907bb52aa1a4' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='EGRNF' AND p.source_sha256='6b94300e0e726a2e27b1a3124dbbf87b3ef97011c09c914989b6712499b40a9d' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='EHOS' AND p.source_sha256='da59fb08a7347142e479027a3fe853fc7b60be495fd8ad432799da08a28a168b' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='ELALF' AND p.source_sha256='069881f982c10347e9cc675db8a3d1a5ee7c9f1e6e2b46b1219f5918cd1ced31' AND p.trade_date>='2021-10-16')
  OR
  (p.symbol='ELCR' AND p.source_sha256='c3012ce09dde5a9c41cf63d1fe968c30d11e5e30b5ec189d5ef0cf62a880e547' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='ELRA' AND p.source_sha256='fa02a09415e95f4d7c9b6ea2319cb35acf23ddd30e7373dad9bc14196c44c291' AND p.trade_date>='2016-10-16')
  OR
  (p.symbol='ENFY' AND p.source_sha256='1f970adc5f8ed809b2b492c0b3ebc2c787092064e2b21b8ec444da2752d323ac' AND p.trade_date>='2011-10-16')
  OR
  (p.symbol='ENTI' AND p.source_sha256='56ae3ad8b0784d23c451f89221db556ee39d17d9594b6412bbdbd6e9dc88df0c' AND p.trade_date>='2021-10-16')
  )
ORDER BY p.trade_date,p.symbol,p.provider;
