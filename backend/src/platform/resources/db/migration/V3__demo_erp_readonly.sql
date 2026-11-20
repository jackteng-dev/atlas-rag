-- Fictional demo dataset for the read-only SQL tool. It is NOT tenant data and holds no real records.
-- Agents never send model-written SQL here: only named, parameterised SELECT templates run, in READ ONLY transactions.
create schema demo_erp;
create table demo_erp.parts (
    part_no varchar(32) primary key,
    name varchar(120) not null,
    category varchar(60) not null,
    voltage_min numeric(6,2), voltage_max numeric(6,2),
    temp_min_c int, temp_max_c int,
    lead_time_days int not null,
    unit_price_twd numeric(12,2) not null
);
create table demo_erp.inventory (
    part_no varchar(32) not null references demo_erp.parts(part_no),
    warehouse varchar(32) not null,
    on_hand int not null,
    primary key (part_no, warehouse)
);
insert into demo_erp.parts values
 ('MCU-100','Demo Microcontroller 100','MCU',1.80,3.60,-40,85,28,82.50),
 ('MCU-200','Demo Microcontroller 200','MCU',2.70,5.50,-40,105,42,129.00),
 ('REG-33','Demo 3.3V Regulator','Power',4.50,18.00,-20,85,14,12.80),
 ('SNS-T1','Demo Temperature Sensor T1','Sensor',2.00,5.50,-55,150,21,31.20),
 ('CAP-47U','Demo 47uF Capacitor','Passive',null,25.00,-55,105,7,0.90);
insert into demo_erp.inventory values
 ('MCU-100','TPE-1',1200),('MCU-100','HSZ-2',300),('MCU-200','TPE-1',80),
 ('REG-33','TPE-1',5000),('SNS-T1','HSZ-2',0),('CAP-47U','TPE-1',20000);
