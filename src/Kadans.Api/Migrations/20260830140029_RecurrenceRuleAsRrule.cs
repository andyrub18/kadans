using System;
using System.Collections.Generic;
using Microsoft.EntityFrameworkCore.Migrations;

#nullable disable

namespace Kadans.Api.Migrations
{
    /// <inheritdoc />
    public partial class RecurrenceRuleAsRrule : Migration
    {
        /// <inheritdoc />
        protected override void Up(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "by_day",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "by_hour",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "by_minute",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "by_month",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "by_month_day",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "by_set_pos",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "count",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "frequency",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "interval",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "until",
                table: "recurrence_rules");

            migrationBuilder.AlterColumn<List<DateTimeOffset>>(
                name: "exceptions",
                table: "recurrence_rules",
                type: "timestamp with time zone[]",
                nullable: false,
                oldClrType: typeof(List<DateTimeOffset>),
                oldType: "timestamp with time zone[]",
                oldNullable: true);

            migrationBuilder.AddColumn<string>(
                name: "rrule",
                table: "recurrence_rules",
                type: "character varying(512)",
                maxLength: 512,
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<string>(
                name: "time_zone_id",
                table: "recurrence_rules",
                type: "character varying(64)",
                maxLength: 64,
                nullable: false,
                defaultValue: "");
        }

        /// <inheritdoc />
        protected override void Down(MigrationBuilder migrationBuilder)
        {
            migrationBuilder.DropColumn(
                name: "rrule",
                table: "recurrence_rules");

            migrationBuilder.DropColumn(
                name: "time_zone_id",
                table: "recurrence_rules");

            migrationBuilder.AlterColumn<List<DateTimeOffset>>(
                name: "exceptions",
                table: "recurrence_rules",
                type: "timestamp with time zone[]",
                nullable: true,
                oldClrType: typeof(List<DateTimeOffset>),
                oldType: "timestamp with time zone[]");

            migrationBuilder.AddColumn<int[]>(
                name: "by_day",
                table: "recurrence_rules",
                type: "integer[]",
                nullable: true);

            migrationBuilder.AddColumn<List<int>>(
                name: "by_hour",
                table: "recurrence_rules",
                type: "integer[]",
                nullable: true);

            migrationBuilder.AddColumn<List<int>>(
                name: "by_minute",
                table: "recurrence_rules",
                type: "integer[]",
                nullable: true);

            migrationBuilder.AddColumn<List<int>>(
                name: "by_month",
                table: "recurrence_rules",
                type: "integer[]",
                nullable: true);

            migrationBuilder.AddColumn<List<int>>(
                name: "by_month_day",
                table: "recurrence_rules",
                type: "integer[]",
                nullable: true);

            migrationBuilder.AddColumn<List<int>>(
                name: "by_set_pos",
                table: "recurrence_rules",
                type: "integer[]",
                nullable: true);

            migrationBuilder.AddColumn<int>(
                name: "count",
                table: "recurrence_rules",
                type: "integer",
                nullable: true);

            migrationBuilder.AddColumn<string>(
                name: "frequency",
                table: "recurrence_rules",
                type: "text",
                nullable: false,
                defaultValue: "");

            migrationBuilder.AddColumn<int>(
                name: "interval",
                table: "recurrence_rules",
                type: "integer",
                nullable: false,
                defaultValue: 0);

            migrationBuilder.AddColumn<DateTimeOffset>(
                name: "until",
                table: "recurrence_rules",
                type: "timestamp with time zone",
                nullable: true);
        }
    }
}
